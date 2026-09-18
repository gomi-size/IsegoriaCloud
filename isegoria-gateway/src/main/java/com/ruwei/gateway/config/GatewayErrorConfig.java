package com.ruwei.gateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruwei.gateway.core.GatewayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// ★ 这两个类在 Boot 3.x 位于 org.springframework.boot.web.reactive.error；
//   Boot 4 已挪到 org.springframework.boot.webflux.error —— 升级 Boot 4 时这两行要一起改（手册 §2.1）
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 网关错误响应统一化：把"路由未命中 / 后端不可用"改成与业务服务一致的 BaseResponse 形状。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>路由未命中 → HTTP 200 + code 40400（与后端"业务错误也返回 200"的契约一致）；</li>
 *   <li>后端实例不可用（NotFoundException / 连接失败）→ HTTP 503 + code 50000
 *       （保留非 200 状态码，便于 Nginx/云监控识别为故障）。</li>
 * </ul>
 *
 * @author ruwei
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class GatewayErrorConfig {

    private final ObjectMapper objectMapper;

    @Bean
    @Order(-1)   // 必须早于 Spring 默认的 DefaultErrorWebExceptionHandler
    public ErrorWebExceptionHandler gatewayErrorWebExceptionHandler() {
        return (exchange, ex) -> {
            ServerHttpResponse response = exchange.getResponse();
            if (response.isCommitted()) {
                return Mono.error(ex);
            }
            GatewayResult body;
            HttpStatus status;
            if (isBackendDown(ex)) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                body = GatewayResult.error("服务暂时不可用，请稍后再试");
                log.error("后端实例不可用 path={} err={}",
                        exchange.getRequest().getPath(), ex.getMessage());
            } else if (ex instanceof ResponseStatusException rse
                    && rse.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                status = HttpStatus.OK;
                body = GatewayResult.notFound();
                log.warn("网关未匹配到路由 path={}", exchange.getRequest().getPath());
            } else {
                status = HttpStatus.OK;
                body = GatewayResult.error("系统错误，请联系管理员");
                log.error("网关异常 path={}", exchange.getRequest().getPath(), ex);
            }
            response.setStatusCode(status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = null;
            try {
                bytes = objectMapper.writeValueAsBytes(body);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        };
    }

    private boolean isBackendDown(Throwable ex) {
        return ex instanceof NotFoundException
                || ex instanceof java.net.ConnectException
                || ex.getCause() instanceof java.net.ConnectException;
    }
}