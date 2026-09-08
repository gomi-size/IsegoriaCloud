package com.ruwei.common.web;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.ruwei.common.web.RateLimit;
import com.ruwei.common.web.RateLimitDimension;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.core.BusinessException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * {@link RateLimit} 限流切面（对齐 docs/modules/12-rate-limit-module.md §5）。
 *
 * <p>Redis Lua 滑动窗口判定（16 文档 §3.3）：ZSet 存窗口内请求时间戳，原子完成
 * 清过期 → 计数 → 记录，超限抛 {@code RATE_LIMIT_ERROR(42900)}，业务代码零侵入。</p>
 *
 * <p><b>fail-open 降级</b>：Redis 异常一律放行 + 告警日志——限流组件失效的代价
 * 小于误伤真实用户；Redis 恢复后自动回到限流态。</p>
 *
 * <p><b>多注解支持</b>：方法上多个 {@link RateLimit}（如忘记密码双档）逐个判定，全部通过才放行。</p>
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    /**
     * 滑动窗口 Lua：KEYS[1]=限流key，ARGV[1]=当前毫秒，ARGV[2]=窗口毫秒，ARGV[3]=上限。
     * 返回 1 放行 / 0 拒绝。
     */
    private static final String SLIDING_WINDOW_LUA = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
            local cnt = redis.call('ZCARD', KEYS[1])
            if cnt < tonumber(ARGV[3]) then
              redis.call('ZADD', KEYS[1], ARGV[1], ARGV[1])
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """;

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private HttpServletRequest request;

    @Around("execution(* com.ruwei.controller..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RateLimit[] rateLimits = method.getAnnotationsByType(RateLimit.class);
        if (rateLimits.length == 0) {
            return pjp.proceed();
        }
        // 多档限流逐个判定，任一超限即拒绝
        for (RateLimit rateLimit : rateLimits) {
            checkRate(rateLimit);
        }
        return pjp.proceed();
    }

    /**
     * 单档限流判定：超限抛 {@code RATE_LIMIT_ERROR}；Redis 异常 fail-open 放行。
     */
    private void checkRate(RateLimit rateLimit) {
        try {
            String key = buildKey(rateLimit);
            Long allowed = stringRedisTemplate.execute(RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(rateLimit.window() * 1000L),
                    String.valueOf(rateLimit.limit()));
            ThrowUtils.throwIf(allowed == null || allowed != 1L, ErrorCode.RATE_LIMIT_ERROR);
        } catch (BusinessException e) {
            // 超限：透传给全局异常处理器（42900）
            throw e;
        } catch (Exception e) {
            // fail-open：限流组件异常不得误伤真实用户
            log.error("限流组件异常，已放行（fail-open），prefix={}, dimension={}",
                    rateLimit.prefix(), rateLimit.dimension(), e);
        }
    }
    /**
     * 维度取 key：USER 已登录按用户、未登录回退 IP；IP 一律按客户端 IP。
     */
    private String buildKey(RateLimit rateLimit) {
        String prefix = "rate:" + rateLimit.prefix() + ":";
        if (rateLimit.dimension() == RateLimitDimension.IP || !StpUtil.isLogin()) {
            return prefix + "ip:" + getClientIp();
        }
        return prefix + "u:" + StpUtil.getLoginIdAsLong();
    }

    /**
     * 客户端 IP：X-Forwarded-For 首值（代理场景），缺省取 remoteAddr。
     */
    private String getClientIp() {
        String xff = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(xff)) {
            return StrUtil.subBefore(xff, ',', false).trim();
        }
        return request.getRemoteAddr();
    }
}
