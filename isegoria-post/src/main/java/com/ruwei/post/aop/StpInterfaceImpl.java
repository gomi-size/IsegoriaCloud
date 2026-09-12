package com.ruwei.post.aop;

import java.util.List;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.entity.User;
import com.ruwei.model.enums.AdminEnum;

import cn.dev33.satoken.stp.StpInterface;

/**
 * Sa-Token 角色数据源（Dubbo 版）：登录态在 Redis 共享，
 * 角色需查 user 表 → 经 {@link InnerUserService} 远程查询 user 服务。
 *
 * <p>user / post 两个服务各放一份（仅这两个服务有 {@code @SaCheckRole} 管理端接口）。
 * 本类在 HTTP 请求线程内被 Sa-Token 调用，存在登录态上下文；但角色判定按
 * loginId 查库完成，不依赖 ThreadLocal 之外的登录态。</p>
 *
 * @author ruwei
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @DubboReference
    private InnerUserService innerUserService;

    /**
     * 返回指定账号的角色列表：admin 表字段非 0 视为管理员。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = innerUserService.getById(Long.valueOf(loginId.toString()));
        if (user == null) {
            return List.of();
        }
        return AdminEnum.isAdmin(user.getAdmin() != null ? user.getAdmin() : 0)
                ? List.of("admin") : List.of("user");
    }

    /**
     * 返回指定账号的权限列表（管理员持全量权限，普通用户仅可发内容）。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        User user = innerUserService.getById(Long.valueOf(loginId.toString()));
        if (user == null) {
            return List.of();
        }
        return AdminEnum.isAdmin(user.getAdmin() != null ? user.getAdmin() : 0)
                ? List.of("user:view:all", "user:update", "user:status",
                          "article:add", "article:update", "article:delete")
                : List.of("article:add");
    }
}
