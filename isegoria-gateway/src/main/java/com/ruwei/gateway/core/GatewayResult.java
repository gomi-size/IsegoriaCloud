package com.ruwei.gateway.core;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关统一响应体。
 *
 * <p><b>字段必须与业务服务的 {@code com.ruwei.common.core.BaseResponse} 逐字一致</b>
 * （code / data / message）——这是前端唯一的错误判断依据，网关不能自创契约。</p>
 *
 * <p>错误码取业务服务的同一套（见 {@code ErrorCode}）：40100 未登录 / 40101 无权限 /
 * 40400 路由不存在 / 42900 限流 / 50000 系统异常。</p>
 *
 * @author ruwei
 */
@Data
public class GatewayResult implements Serializable {

    /** 未登录 */
    public static final int CODE_NOT_LOGIN = 40100;
    /** 无权限 */
    public static final int CODE_NO_AUTH = 40101;
    /** 路由/资源不存在 */
    public static final int CODE_NOT_FOUND = 40400;
    /** 限流 */
    public static final int CODE_RATE_LIMIT = 42900;
    /** 系统异常 */
    public static final int CODE_SYSTEM = 50000;

    private int code;
    private Object data;
    private String message;

    public GatewayResult(int code, Object data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public static GatewayResult notLogin() {
        return new GatewayResult(CODE_NOT_LOGIN, null, "未登录");
    }

    public static GatewayResult noAuth() {
        return new GatewayResult(CODE_NO_AUTH, null, "无权限");
    }

    public static GatewayResult notFound() {
        return new GatewayResult(CODE_NOT_FOUND, null, "接口不存在");
    }

    public static GatewayResult rateLimited() {
        return new GatewayResult(CODE_RATE_LIMIT, null, "操作过于频繁，请稍后再试");
    }

    public static GatewayResult error(String message) {
        return new GatewayResult(CODE_SYSTEM, null, message);
    }

    /** 便于在配置文件/日志里打印（不含 data） */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}