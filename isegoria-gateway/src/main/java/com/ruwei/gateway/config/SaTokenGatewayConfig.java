package com.ruwei.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.ruwei.gateway.core.GatewayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关统一鉴权配置（Sa-Token Reactor 版）。
 *
 * <p>只做"是否登录"的粗筛：角色/权限细判留在业务服务（网关不该懂业务权限）。
 * 业务服务内的 {@code @SaCheckLogin} / {@code @SaCheckRole} <b>保留不删</b>——纵深防御，
 * 万一网关漏了某条路由、或有人绕过网关直连，服务内还能兜住。</p>
 *
 * <p>响应契约：<b>必须与业务服务一致</b> —— HTTP 200 + {@code {"code":40100,"data":null,"message":"未登录"}}。
 * 若网关自己返回 HTTP 401 + 别的 body 形状，前端按 {@code code} 判断登录态的拦截器会漏判，
 * 表现为"登录过期后页面不跳登录页、而是显示各种诡异错误"。</p>
 *
 * @author ruwei
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SaTokenGatewayConfig {

    private final GatewayAuthProperties authProperties;

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .setAuth(obj -> {
                    if (isWhitelistMode()) {
                        // ---- 模式二：窄白名单（phase9 写法）。默认全拦，只放行 public-paths ----
                        // 注意失败模式：漏配任何游客接口 = 该功能 401
                        log.debug("网关鉴权（whitelist）path={}", SaHolder.getRequest().getRequestPath());
                        SaRouter.match("/**").check(r -> StpUtil.checkLogin());
                        return;
                    }
                    // ---- 模式一（推荐）：只对"明确需要登录的整组路径"做粗筛 ----
                    // notMatch(publicPaths)：公开接口显式豁免（保护组优先于公开组）
                    SaRouter.match(authProperties.getProtectedPaths())
                            .notMatch(authProperties.getPublicPaths())
                            .check(r -> StpUtil.checkLogin());
                })
                .setError(e -> {
                    // ★ 契约：与业务服务 GlobalExceptionHandler 一致（HTTP 200 + code 语义）
                    if (e instanceof NotLoginException) {
                        log.info("网关拦截未登录请求 path={} reason={}",
                                SaHolder.getRequest().getRequestPath(), e.getMessage());
                        return GatewayResult.notLogin();
                    }
                    if (e instanceof NotRoleException || e instanceof NotPermissionException) {
                        log.info("网关拦截无权限请求 path={}", SaHolder.getRequest().getRequestPath());
                        return GatewayResult.noAuth();
                    }
                    log.error("网关鉴权异常 path={}", SaHolder.getRequest().getRequestPath(), e);
                    return GatewayResult.error("系统错误，请联系管理员");
                });
    }

    private boolean isWhitelistMode() {
        return "whitelist".equalsIgnoreCase(authProperties.getMode());
    }
}