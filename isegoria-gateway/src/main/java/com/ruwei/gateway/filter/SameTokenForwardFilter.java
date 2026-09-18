package com.ruwei.gateway.filter;

import cn.dev33.satoken.same.SaSameUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Same-Token 注入过滤器：给每个转发到业务服务的请求加上 {@code SA-SAME-TOKEN} 头。
 *
 * <p>与业务服务侧的校验过滤器配合，构成"只有经过网关的请求才能进服务"的边界
 * （业务侧实现见 docs/phase13-gateway-scg.md §7.3）。</p>
 *
 * <p><b>★ 为什么套一层 {@link Schedulers#boundedElastic()}</b>：
 * Sa-Token 的 {@code SaTokenDao} 是<b>阻塞式</b>实现（Spring Data Redis 同步 API），
 * 而 GlobalFilter 跑在 Netty 事件循环线程上 —— 直接调用会<em>阻塞事件循环</em>，
 * 高并发下吞吐骤降、并触发 Reactor 的 "blocking call" 告警。
 * 因此这里把取 Same-Token 的动作丢到弹性线程池，拿到值后再回到事件循环继续转发。
 * （同类问题在 Sa-Token 官方的 {@code SaReactorFilter} 里也存在且无法绕开，
 * 详见手册 §7.1 末的说明。）</p>
 *
 * <p>注意：本过滤器只加不删，且<b>不修改 Cookie</b> —— notify 的 WS 握手靠 {@code isegoria}
 * 这个 Cookie 取 token，任何对 Cookie 的改写都会让 WS 认证失败。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class SameTokenForwardFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.fromCallable(SaSameUtil::getToken)
                // ★ 阻塞式读 Redis → 必须在弹性线程池执行，不能占事件循环
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sameToken -> {
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .header(SaSameUtil.SAME_TOKEN, sameToken)
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                })
                .onErrorResume(e -> {
                    // Same-Token 取不到时**不放行**：宁可报错也不能放一个没有边界标记的请求进去
                    log.error("Same-Token 获取失败，拒绝转发 path={}", exchange.getRequest().getPath(), e);
                    return Mono.error(e);
                });
    }

    /**
     * 顺序：必须早于路由转发（{@code NettyRoutingFilter} 的 order 是最大整数），
     * 但要晚于 {@code TraceIdFilter}（+50），这样本过滤器打的日志也带 traceId。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }
}
