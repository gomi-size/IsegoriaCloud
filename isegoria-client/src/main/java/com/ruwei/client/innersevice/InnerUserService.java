package com.ruwei.client.innersevice;

import com.ruwei.model.domain.empty.Board;
import com.ruwei.model.domain.empty.Comment;
import com.ruwei.model.domain.empty.Post;
import com.ruwei.model.domain.empty.User;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

