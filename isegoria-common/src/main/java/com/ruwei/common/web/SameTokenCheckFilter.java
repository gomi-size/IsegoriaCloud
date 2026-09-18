package com.ruwei.common.web;

import cn.dev33.satoken.same.SaSameUtil;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内网边界校验：只接受携带合法 Same-Token 的请求（即"必须经过网关"）。
 *
 * <p><b>默认关闭</b>（{@code gateway.same-token.enabled=false}），网关 P3 阶段上线时，
 * 在 6 个服务的配置里（或 compose 的统一环境变量里）打开。</p>
 *
 * <p><b>放行清单</b>（否则运维与本地联调会很难受）：</p>
 * <ul>
 *   <li>{@code /actuator/**}：存活探测（Nginx/云监控/容器 healthcheck）；</li>
 *   <li>Knife4j / Swagger 文档：{@code /doc.html}、{@code /swagger-ui.html}、{@code /v3/api-docs/**}、{@code /webjars/**}；</li>
 *   <li><b>{@code OPTIONS} 请求</b>：CORS 预检由浏览器自动发出、不带业务数据与 Cookie，
 *       拦它只会让前端跨域整体失败；</li>
 *   <li>本地联调：{@code gateway.same-token.allow-localhost=true} 时放行来自回环地址的请求。</li>
 * </ul>
 *
 * <p>⚠️ Dubbo 内部调用走 tri 协议，不经过本过滤器，不受影响。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gateway.same-token.enabled", havingValue = "true")
public class SameTokenCheckFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${gateway.same-token.allow-localhost:false}")
    private boolean allowLocalhost;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        // ★ CORS 预检必须放行：它是浏览器自动发的、不带 Cookie/业务数据，
        //   拦掉会让"服务还在被前端直连"或"网关 CORS 配置过渡期"直接跨域失败
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || isExcluded(uri)
                || (allowLocalhost && isLoopback(request.getRemoteAddr()))) {
            filterChain.doFilter(request, response);
            return;
        }
        String sameToken = request.getHeader(SaSameUtil.SAME_TOKEN);
        if (!StringUtils.hasText(sameToken)) {
            reject(response, "无效 Same-Token：请通过网关访问");
            return;
        }
        try {
            SaSameUtil.checkToken(sameToken);
        } catch (Exception e) {
            log.warn("Same-Token 校验失败 uri={} ip={}", uri, request.getRemoteAddr());
            reject(response, "无效 Same-Token：请通过网关访问");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isExcluded(String uri) {
        return uri.startsWith("/actuator")
                || uri.startsWith("/doc.html")
                || uri.startsWith("/swagger-ui.html")
                || uri.startsWith("/swagger-resources")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/v2/api-docs")
                || uri.startsWith("/webjars")
                || uri.startsWith("/favicon.ico");
    }

    private boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }

    /** 契约同全局异常处理：HTTP 200 + BaseResponse(code=40100)，避免前端误判为网络故障 */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        BaseResponse<?> body = ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}