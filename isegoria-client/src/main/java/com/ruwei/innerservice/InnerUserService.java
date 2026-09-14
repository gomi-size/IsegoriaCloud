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

    /**
     * 按对外编码（user.userId，Redis 自增 base 100000）查用户。
     *
     * <p>关注关系表的存储键一律是<b>内部主键 id</b>，但对外接口允许前端只传 userId，
     * 因此需要本方法做解析（旧单体 {@code UserFollowServiceImpl.resolveTargetInternalId}
     * 直接 {@code userService.lambdaQuery().eq(User::getUserId, userId).one()}）。</p>
     *
     * <p>注意与 {@link #getById(Serializable)} 的区别：那个查的是内部主键 id，
     * 两个 id 数值域不重叠（外部 id 从 100000 起自增，内部是雪花 19 位），但
     * <b>调用方必须自己明确传的是哪一个</b>，不要混用。</p>
     *
     * @param userId 用户对外编码
     * @return 用户实体；userId 为空或不存在返回 {@code null}
     */
    User getByUserId(Long userId);
}

