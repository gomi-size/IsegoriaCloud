package com.ruwei.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（对齐 docs/modules/12-rate-limit-module.md §5.1）。
 *
 * <p>由 {@code RateLimitAspect} 以 Redis Lua 滑动窗口判定：窗口内请求数达到 {@link #limit()}
 * 后继续请求抛「操作太频繁」（42900）。规则随代码走，改阈值即改注解。</p>
 *
 * <p>支持重复标注（{@link Repeatable}）：同一方法可配多档限流，全部通过才放行，
 * 例如忘记密码「1 分钟 1 次 + 60 分钟 5 次」双注解。</p>
 *
 * <pre>
 * // 登录用户 1 分钟最多 5 次
 * &#64;RateLimit(limit = 5, window = 60, prefix = "post")
 * // 匿名接口按 IP：10 分钟 10 次
 * &#64;RateLimit(dimension = RateLimitDimension.IP, limit = 10, window = 600, prefix = "register")
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimit.List.class)
public @interface RateLimit {

    /**
     * 限流维度：{@link RateLimitDimension#USER}（默认，已登录按用户、未登录回退 IP）/
     * {@link RateLimitDimension#IP}（一律按 IP）。
     */
    RateLimitDimension dimension() default RateLimitDimension.USER;

    /**
     * 窗口内允许次数。
     */
    int limit();

    /**
     * 窗口大小（秒）。
     */
    int window() default 60;

    /**
     * key 业务前缀：最终 key = rate:{prefix}:u:{loginId} 或 rate:{prefix}:ip:{ip}。
     * 相同 prefix 的接口共享同一窗口计数（如发帖/编辑/发布草稿共用 "post"）。
     */
    String prefix() default "default";

    /**
     * 支持同一方法叠加多个 {@link RateLimit} 的容器注解（由 {@link Repeatable} 自动生成语义）。
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        RateLimit[] value();
    }
}
