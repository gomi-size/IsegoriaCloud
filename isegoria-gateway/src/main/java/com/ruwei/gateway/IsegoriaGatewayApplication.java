package com.ruwei.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IsegoriaForum 业务网关启动类（Spring Cloud Gateway 5.x / WebFlux 响应式栈）。
 *
 * <p>职责边界（写死，别腐化）：<b>只做横切关注点</b> —— 路由转发、Sa-Token 登录态粗筛、
 * Same-Token 注入、限流、CORS 统一、traceId 注入。
 * <b>严禁在此工程写业务逻辑与接口聚合</b>；确实需要聚合就另立 BFF 服务
 * （见 docs/phase13-gateway-scg.md §1.1 与 phase9 §10）。</p>
 *
 * <p>{@code @EnableScheduling} 供 Same-Token 主动刷新任务使用（手册 §7.2）。</p>
 *
 * @author ruwei
 */
@SpringBootApplication
@EnableScheduling
public class IsegoriaGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsegoriaGatewayApplication.class, args);
    }
}
