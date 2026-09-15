package com.ruwei.notify.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** 与登录逻辑/SaTokenConfigure 保持一致的 token cookie 名 */
    private static final String TOKEN_COOKIE = "isegoria";
    /** 握手阶段把内部 id 暂存到 session attributes 的 key */
    static final String LOGIN_ID_ATTR = "loginId";

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 端点 /ws（context-path 下即 /api/ws）；withSockJS 兼容无原生 WS 的环境
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new AuthHandshakeInterceptor())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");   // 服务端→客户端的广播/点对点前缀
        registry.setUserDestinationPrefix("/user");          // /user/{id}/queue/notify 用户私有目的地
        registry.setApplicationDestinationPrefixes("/app");   // 客户端→服务端发送前缀
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new AuthChannelInterceptor());
    }

    /** ① 握手拦截器：HTTP 升级为 WS 时校验 Sa-Token，把内部 id 存入 session attributes */
    static class AuthHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                var cookies = servletRequest.getServletRequest().getCookies();
                String token = null;
                if (cookies != null) {
                    for (var c : cookies) {
                        if (TOKEN_COOKIE.equals(c.getName())) { token = c.getValue(); break; }
                    }
                }
                if (token != null) {
                    try {
                        Object loginId = StpUtil.getLoginIdByToken(token);
                        if (loginId != null) {
                            attributes.put(LOGIN_ID_ATTR, Long.valueOf(loginId.toString()));
                            return true;
                        }
                    } catch (Exception e) {
                        log.warn("WS handshake token invalid", e);
                    }
                }
            }
            return false; // 未登录/令牌无效 → 拒绝握手
        }
        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception ex) { }
    }

    /** ② 入站拦截器：在 CONNECT 帧上设置 Principal（name=内部id），使 /user/{id} 路由正确 */
    static class AuthChannelInterceptor implements ChannelInterceptor {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                Long loginId = (Long) accessor.getSessionAttributes().get(LOGIN_ID_ATTR);
                if (loginId != null) {
                    final long uid = loginId;
                    accessor.setUser(() -> String.valueOf(uid)); // Principal.getName() = 内部id
                }
            }
            return message;
        }
    }
}