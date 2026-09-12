package com.ruwei.user.inner;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.apache.dubbo.config.annotation.DubboService;

import com.ruwei.common.mybatis.CountUtils;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.entity.User;
import com.ruwei.model.enums.AdminEnum;
import com.ruwei.user.service.UserService;

import jakarta.annotation.Resource;

/**
 * 用户契约的 Dubbo provider（供 post / interaction / social / notify / rec 远程调用）。
 *
 * <p>查询类方法均为"按 id 查库"的幂等读，不校验调用方登录态；
 * {@link #incrementUserCount} 是唯一的写方法（审核流中维护作者 postCount），
 * 计数留在 user 服务执行，其他服务不直改 user 表。</p>
 *
 * @author ruwei
 */
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
        // admin 字段为 null（旧数据未补列）时按普通用户处理，避免拆箱 NPE
        return user != null && AdminEnum.isAdmin(user.getAdmin() != null ? user.getAdmin() : 0);
    }

    @Override
    public String getNickname(Long userId) {
        User user = userService.getById(userId);
        return user == null ? null : user.getNickname();
    }

    @Override
    public void incrementUserCount(Long userId, String column, int delta) {
        // 计数留在 user 服务执行（DB 层原子增减）；userId 为空或 delta=0 直接跳过
        if (userId == null || delta == 0) {
            return;
        }
        CountUtils.increment(userService, User::getId, userId, column, delta);
    }
}
