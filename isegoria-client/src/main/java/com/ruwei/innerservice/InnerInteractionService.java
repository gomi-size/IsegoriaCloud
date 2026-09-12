package com.ruwei.innerservice;

import java.util.List;
import java.util.Map;

import com.ruwei.model.entity.PostCollect;

/** interaction 服务提供（InnerInteractionServiceImpl @DubboService） */
public interface InnerInteractionService {

    /**
     * 批量查询是否点赞帖子
     * @param userId 用户内部 id
     * @param postIds 帖子内部 id 集合
     * @return postId → 是否点赞
     */
    Map<Long, Boolean> getPostLikedMap(Long userId, List<Long> postIds);

    /**
     * 批量查询是否收藏帖子
     * @param userId 用户内部 id
     * @param postIds 帖子内部 id 集合
     * @return postId → 是否收藏
     */
    Map<Long, Boolean> getPostCollectedMap(Long userId, List<Long> postIds);

    /**
     * 批量是否点赞评论
     * @param userId 用户内部 id
     * @param commentIds 评论内部 id 集合
     * @return commentId → 是否点赞
     */
    Map<Long, Boolean> getCommentLikedMap(Long userId, List<Long> commentIds);

    /**
     * 分页查询用户收藏关系（默认收藏夹，最近收藏优先）。
     *
     * <p>「我的收藏」列表由 post 服务装配帖子信息，收藏关系归 interaction；
     * 为避免把 MyBatis-Plus 的 {@code Page} 类型引入 Dubbo 契约，
     * 这里只返回当页关系列表，总数另经 {@link #countMyCollect(Long)} 查询。</p>
     *
     * @param userId 用户内部 id
     * @param current 页码（1 起）
     * @param pageSize 页大小
     * @return 当页收藏关系（PostCollect），按收藏时间倒序；无数据返回空列表
     */
    List<PostCollect> pageMyCollectRelations(Long userId, long current, long pageSize);

    /**
     * 统计用户收藏关系总数（{@link #pageMyCollectRelations(Long, long, long)} 的分页 total）。
     *
     * @param userId 用户内部 id
     * @return 收藏总数
     */
    long countMyCollect(Long userId);
}
