package com.ruwei.user.aop;

import cn.dev33.satoken.stp.StpInterface;
import com.ruwei.model.entity.User;
import com.ruwei.model.enums.AdminEnum;
import com.ruwei.user.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 角色数据源（Dubbo 版）：登录态在 Redis 共享，
 * 角色需查 user 表 → 经 InnerUserService 远程查询。
 * user / post 两个服务各放一份（仅这两个服务有 @SaCheckRole 管理端接口）。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private UserService userService;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = userService.getById(Long.valueOf(loginId.toString()));
        if (user == null) return List.of();
        return AdminEnum.isAdmin(user.getAdmin() != null ? user.getAdmin() : 0)
                ? List.of("admin") : List.of("user");
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        User user = userService.getById(Long.valueOf(loginId.toString()));
        if (user == null) return List.of();
        return AdminEnum.isAdmin(user.getAdmin() != null ? user.getAdmin() : 0)
                ? List.of("user:view:all", "user:update", "user:status",
                          "article:add", "article:update", "article:delete")
                : List.of("article:add");
    }
}