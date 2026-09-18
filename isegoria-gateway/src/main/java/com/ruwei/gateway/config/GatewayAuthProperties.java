package com.ruwei.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权策略配置（白名单 / 保护组 / 模式）。
 *
 * <p>两种模式的失败模式完全不同，务必理解后再选（见手册 §6.3）：</p>
 * <ul>
 *   <li>{@code blacklist}（推荐）：默认**不校验**，只对 {@link #protectedPaths} 里明确列出的
 *       "整组需要登录"的路径做粗筛。漏配的后果 = 该接口少了网关这层粗筛，
 *       但业务服务内的 {@code @SaCheckLogin} 仍会拦住 → <b>不会造成线上功能不可用</b>。</li>
 *   <li>{@code whitelist}：默认**全拦**，只放行 {@link #publicPaths}。漏配的后果 =
 *       游客接口被 401，<b>游客功能整体不可用</b>。</li>
 * </ul>
 *
 * @author ruwei
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gateway.auth")
public class GatewayAuthProperties {

    /** 策略模式：blacklist（默认，推荐）| whitelist */
    private String mode = "blacklist";

    /** 游客可访问 / 无需网关校验的路径（Sa 通配：* 单层，** 多层） */
    private List<String> publicPaths = new ArrayList<>();

    /** 明确需要登录的路径（blacklist 模式使用；保护组优先于公开组） */
    private List<String> protectedPaths = new ArrayList<>();
}