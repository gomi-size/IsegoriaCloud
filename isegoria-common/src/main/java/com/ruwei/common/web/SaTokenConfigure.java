package com.ruwei.common.web;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器注册配置（六服务共享）。
 *
 * <p>{@code sa-token-spring-boot3-starter} 只提供自动装配能力，<b>不会</b>自动把
 * {@link SaInterceptor} 注册到 Spring MVC 的拦截器链上，因此
 * {@code @SaCheckLogin} / {@code @SaCheckRole} / {@code @SaCheckPermission} 等注解
 * 必须由本类显式注册后才会生效——这是官方 Quickstart 的强制步骤，漏配会导致
 * 标注了鉴权注解的接口被匿名放行。</p>
 *
 * <p>放行清单只包含接口文档与静态资源；业务接口一律拦截，由各 Controller 上的
 * Sa-Token 注解做细粒度鉴权（无注解的接口即视为公开接口）。</p>
 *
 * @author ruwei
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    /**
     * 注册 Sa-Token 拦截器。
     *
     * <p>拦截 {@code /**} 全部路径，仅放行 Knife4j / Swagger 文档与静态资源，
     * 避免文档页被登录校验拦截而无法访问。</p>
     *
     * @param registry Spring MVC 拦截器注册表（由容器注入）
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                // 放行 Swagger / Knife4j 文档与静态资源，避免被鉴权拦截
                .excludePathPatterns(
                        "/doc.html",
                        "/swagger-ui.html",
                        "/swagger-resources/**",
                        "/v3/api-docs/**",
                        "/v2/api-docs/**",
                        "/webjars/**",
                        "/favicon.ico"
                );
    }
}
