package com.ruwei.common.web;

/**
 * 限流维度（对齐 docs/modules/12-rate-limit-module.md §5.1）。
 */
public enum RateLimitDimension {

    /**
     * 登录用户维度：key = rate:{prefix}:u:{loginId}。
     * 未登录请求回退 IP 维度兜底（正常不会发生：USER 维度接口均受 @SaCheckLogin 保护）。
     */
    USER,

    /**
     * IP 维度：key = rate:{prefix}:ip:{ip}。
     * 公开接口专用（注册/登录/忘记密码等），登录与否均按 IP 计数。
     */
    IP
}
