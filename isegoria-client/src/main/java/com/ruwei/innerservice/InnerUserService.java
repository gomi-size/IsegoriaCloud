package com.ruwei.innerservice;

import com.ruwei.model.entity.User;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** user 服务提供（InnerUserServiceImpl @DubboService） */
public interface InnerUserService {
    /**
     * 根据id获取用户
     * @param id
     * @return
     */
    User getById(Serializable id);

    /**
     * 批量取作者信息
     * @param ids
     * @return
     */
    List<User> listByIds(Collection<? extends Serializable> ids);

    /**
     * 判断是否是管理员
     * @param loginId
     * @return
     */
    boolean isAdmin(Long loginId);

    /**
     * 获取用户昵称
     * @param userId
     * @return
     */
    String getNickname(Long userId);

    /**
     * 用户计数字段原子增减（审核流中维护作者 postCount，语义对齐 CountUtils.increment）。
     *
     * <p>计数留在 user 服务执行（post 表归 post、user 计数归 user，互不直改对方表）；
     * {@code column} 走服务端白名单语义（当前仅 {@code postCount}），调用方勿拼用户输入。</p>
     *
     * @param userId 用户内部 id
     * @param column 计数列名（当前仅支持 "postCount"）
     * @param delta  增量（正数加、负数减）
     */
    void incrementUserCount(Long userId, String column, int delta);
}

