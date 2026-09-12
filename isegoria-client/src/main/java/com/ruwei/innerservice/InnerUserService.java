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
}

