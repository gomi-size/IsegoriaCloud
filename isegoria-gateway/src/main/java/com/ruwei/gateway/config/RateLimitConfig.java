package com.ruwei.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 限流 Key 解析器（配合 RequestRateLimiter 过滤器使用，见 application.yml 的路由 filters）。
 *
 * <p>按 IP 限流是兜底（挡爬虫/刷接口）；按用户限流更精确（读 Cookie 里的 token 作为身份指纹，
 * <b>不需要解析会话</b>——把 token 原文哈希即可，避免网关与业务服务的会话格式耦合）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    /** Cookie 名与 Sa-Token 的 token-name 一致 */
    private static final String COOKIE_NAME = "isegoria";
    private static final String HEADER_NAME = "isegoria";

    /**
     * 按客户端 IP：优先 X-Forwarded-For 首值（Nginx 加的），回退 remoteAddress。
     *
     * <p>⚠️ 前提是网关的 {@code spring.cloud.gateway.server.webflux.trusted-proxies} 配了 Nginx 的 IP，
     * 否则拿到的 remoteAddress 是网关自己。</p>
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (StringUtils.hasText(xff)) {
                return Mono.just("ip:" + xff.split(",")[0].trim());
            }
            return Mono.just("ip:" + clientIp(exchange));
        };
    }

    /** 取远端地址，并把"InetSocketAddress 在但 address 为空"这种边角情况一并兜住（否则解析器抛异常 → 请求 500） */
    private static String clientIp(org.springframework.web.server.ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    /** 按登录身份：token 原文取哈希前 16 位（无法反推，且不依赖 Sa-Token 会话格式） */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String token = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);
            if (!StringUtils.hasText(token)) {
                HttpCookie cookie = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
                if (cookie != null) {
                    token = cookie.getValue();
                }
            }
            if (StringUtils.hasText(token)) {
                // 不落原始 token（避免 Redis 里出现可用的登录凭证），只放一个短指纹
                return Mono.just("u:" + Integer.toHexString(token.hashCode()));
            }
            // 未登录 → 回退按 IP，且与 ipKeyResolver 的 key 格式保持一致（便于在 Redis 里对账）
            return Mono.just("ip:" + clientIp(exchange));
        };
    }
}