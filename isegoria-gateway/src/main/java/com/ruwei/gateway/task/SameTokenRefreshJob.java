package com.ruwei.gateway.task;

import cn.dev33.satoken.same.SaSameUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Same-Token 主动刷新任务。
 *
 * <p>为什么需要它：Same-Token 默认有效期 1 天、带"自刷新"机制，但官方说明自刷新在高并发下
 * 会造成毫秒级服务失效；生产应<b>专门用一个定时任务主动刷新</b>，且刷新间隔要远小于有效期。</p>
 *
 * <p>多实例安全：网关若部署 2 个实例，两个实例都会跑本任务 → 用 Redis SETNX 竞争锁，
 * 保证同一时刻集群内只有一次刷新（Sa-Token 刷新时旧 Token 会作为"次级 Token"保留到下次刷新，
 * 因此刷新瞬间不会有请求被拒）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SameTokenRefreshJob {

    /** 全局刷新锁：TTL 比刷新间隔略短，保证锁不会长期占住 */
    private static final String LOCK_KEY = "lock:same-token:refresh";
    private static final Duration LOCK_TTL = Duration.ofMinutes(4);

    private final StringRedisTemplate stringRedisTemplate;

    /** 每 5 分钟一次（cron 六段式：秒 分 时 日 月 周） */
    @Scheduled(cron = "0 */5 * * * *")
    public void refresh() {
        try {
            Boolean got = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
            if (!Boolean.TRUE.equals(got)) {
                return;      // 其它实例正在刷 / 刚刷过
            }
            SaSameUtil.refreshToken();
            log.info("Same-Token 已刷新");
        } catch (Exception e) {
            log.error("Same-Token 刷新失败（下个周期会重试）", e);
        }
    }
}