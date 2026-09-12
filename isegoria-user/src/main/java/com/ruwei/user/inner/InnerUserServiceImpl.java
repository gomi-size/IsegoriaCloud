package com.ruwei.user.inner;

import cn.dev33.satoken.stp.StpUtil;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.entity.User;
import com.ruwei.model.enums.AdminEnum;
import com.ruwei.user.service.UserService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

@DubboService
public class InnerUserServiceImpl implements InnerUserService {

    @Resource
    private UserService userService;

    @Override
    public User getById(Serializable id) {
        return userService.getById(id);
    }

    @Override
    public List<User> listByIds(Collection<? extends Serializable> ids) {
        return userService.listByIds(ids);
    }

    @Override
    public boolean isAdmin(Long loginId) {
        if (loginId == null) {
            return false;
        }
        User user = getById(loginId);
        return user != null && AdminEnum.isAdmin(user.getAdmin() != null ? user.getAdmin() : 0);   // ← admin 为 null 时 NPE   // 按 id 查 admin 字段，不依赖调用方登录态
    }

    @Override
    public String getNickname(Long userId) {
        User user = userService.getById(userId);
        return user.getNickname();
    }
}