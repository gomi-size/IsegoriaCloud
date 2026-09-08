package com.ruwei.common.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.BusinessException;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一将异常转换为 {@link BaseResponse}，保证前后端错误契约一致。
 * 处理优先级由 Spring 按异常类型 specificity 自动排序（越具体越先命中）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：ThrowUtils / BusinessException 主动抛出的可预期错误
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> handleBusinessException(BusinessException e) {
        // 返回异常自带的 code 与 message，契约与 ResultUtils 一致
        log.error("出现错误{}", e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * Sa-Token 未登录异常：@SaCheckLogin 等鉴权注解触发
     * 注意：未登录/登录态失效是正常业务状态，不是系统故障，禁止用 ERROR 记录（否则日志刷屏 + 误报警）
     */
    @ExceptionHandler(NotLoginException.class)
    public BaseResponse<?> handleNotLoginException(NotLoginException e) {
        log.error("未登录拦截（{}）", e.getMessage());
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR);
    }

    /**
     * Sa-Token 角色不足：@SaCheckRole("admin") 校验失败（非管理员访问管理员接口）
     * 正常业务拒绝，非系统故障，debug 级别即可
     */
    @ExceptionHandler(NotRoleException.class)
    public BaseResponse<?> handleNotRoleException(NotRoleException e) {
        log.error("角色不足（需要管理员）：{}", e.getMessage());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR,"只要管理员才能访问");
    }

    /**
     * Sa-Token 权限不足：@SaCheckPermission 校验失败
     */
    @ExceptionHandler(NotPermissionException.class)
    public BaseResponse<?> handleNotPermissionException(NotPermissionException e) {
        log.error("权限不足：{}", e.getMessage());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR,"只要管理员才能访问");
    }

    /**
     * 兜底：未预期的系统异常，避免把堆栈直接抛给前端（安全 + 契约统一）
     */
    @ExceptionHandler(Exception.class)
    public BaseResponse<?> handleException(Exception e) {
        log.error("系统未处理异常，请联系管理员：", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR,"系统错误请联系管理员");
    }
}
