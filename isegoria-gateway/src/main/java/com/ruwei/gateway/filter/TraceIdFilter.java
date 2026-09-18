package com.ruwei.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * traceId 注入 + 统一访问日志。
 *
 * <p>已有 {@code X-Trace-Id} 就复用（便于把外部链路串起来），否则生成一个 16 位短 ID，
 * 并注入到转发请求头里 —— 业务服务只要在日志格式里带上该头，就能做到
 * "网关 → 服务 → Dubbo → MQ" 全链路可查（业务服务侧的 MDC 接入是另一件事，见 §8.3 末）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        final String finalTraceId = traceId;
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, finalTraceId)
                .build();
        long start = System.currentTimeMillis();
        return chain.filter(exchange.mutate().request(request).build())
                .then(Mono.fromRunnable(() -> {
                    var status = exchange.getResponse().getStatusCode();
                    log.info("[{}] {} {} -> {} ({} ms)", finalTraceId,
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getPath(),
                            status == null ? "-" : status.value(),
                            System.currentTimeMillis() - start);
                }));
    }

    /**
     * 顺序：要排在**本项目所有 GlobalFilter 的最外层**（SameTokenForwardFilter 是 +150），
     * 这样后面任何过滤器打的日志都带 traceId。
     *
     * <p>⚠️ 局限：Sa-Token 的 {@code SaReactorFilter} 是 WebFilter，在 GlobalFilter 之前执行，
     * 它拦下的请求（如 40100）日志里不会有 traceId —— 想看那种日志只能另加一个更早的 WebFilter，
     * 本项目暂不为此加复杂度。</p>
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}