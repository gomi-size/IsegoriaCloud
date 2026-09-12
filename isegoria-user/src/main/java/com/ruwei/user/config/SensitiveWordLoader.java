package com.ruwei.user.config;

import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.innerservice.InnerSensitiveWordService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 敏感词本地缓存装配：启动时经 Dubbo 从 post 服务拉全量词表，每 5 分钟兜底刷新。
 * （post 服务未上线期间容错：拉取失败沿用旧词表；空词表 = 不过滤，与旧版"表空"行为一致）
 */
@Slf4j
@Configuration
public class SensitiveWordLoader {

    @DubboReference
    private InnerSensitiveWordService innerSensitiveWordService;

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    @Bean
    public ApplicationRunner sensitiveWordInit() {
        return args -> refresh();
    }

    @Scheduled(fixedDelay = 300_000)
    public void refresh() {
        try {
            sensitiveWordFilter.setWords(innerSensitiveWordService.getAllWords());
        } catch (Exception e) {
            log.warn("敏感词表拉取失败（post 服务未就绪？），沿用旧词表: {}", e.getMessage());
        }
    }
}