package com.ruwei.post.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.post.service.SensitiveWordService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 敏感词本地缓存装配（post 服务版）：启动时从本域 sensitive_word 表加载全量词表，
 * 每 5 分钟兜底刷新，与 {@code SensitiveWordServiceImpl} 的"写后即时刷新"互补。
 *
 * <p>post 是词表属主，词表就在本域，<b>无需</b>像其他服务那样经 Dubbo 从 post 拉取；
 * 直接查 {@link SensitiveWordService} 后灌入 {@link SensitiveWordFilter#setWords(List)}。
 * 加载失败时沿用旧词表（启动首载失败 = 空词表 = 不过滤，与旧版"表空"行为一致）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Configuration
public class SensitiveWordLoader {

    @Resource
    private SensitiveWordService sensitiveWordService;

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    /**
     * 启动时装载一次词表（ApplicationRunner 在容器就绪后执行）。
     */
    @Bean
    public ApplicationRunner sensitiveWordInit() {
        return args -> refresh();
    }

    /**
     * 每 5 分钟兜底刷新（管理端 CRUD 已有写后即时刷新，这里兜底防漏）。
     */
    @Scheduled(fixedDelay = 300_000)
    public void refresh() {
        try {
            sensitiveWordFilter.setWords(sensitiveWordService.loadAllWords());
        } catch (Exception e) {
            log.warn("敏感词表加载失败，沿用旧词表: {}", e.getMessage());
        }
    }
}
