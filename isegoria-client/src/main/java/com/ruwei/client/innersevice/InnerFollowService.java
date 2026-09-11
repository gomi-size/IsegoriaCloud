package com.ruwei.client.innersevice;

import java.util.List; /** social 服务提供（InnerFollowServiceImpl @DubboService） */
public interface InnerFollowService {
    /**
     * like/comment/rec 共用，不可降级
     * @param followerId 关注着
     * @param followeeId 被关注者
     * @return
     */
    boolean isFollowing(Long followerId, Long followeeId);

    /**
     * 发帖审核通过后粉丝通知
     * @param followeeId 被关注着
     * @return 返回粉丝id列表
     */
    List<Long> listFanIds(Long followeeId);

    /**
     *
     * @param followerId 关注者
     * @return 获取所有关注列表
     */
    List<Long> listFolloweeIds(Long followerId);

    /**
     * 获取关注板块的id
     * @param userId 用户
     * @return
     */
    List<Long> listFollowedBoardIds(Long userId);
}
