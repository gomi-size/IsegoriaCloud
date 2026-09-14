package com.ruwei.interaction.service;



import com.ruwei.model.vo.LikeToggleVO;

import java.util.Collection;
import java.util.Map;

public interface LikeService {

    /** 帖子点赞 toggle，返回切换后 {isLiked, likeCount} */
    LikeToggleVO togglePostLike(String postCode);

    /** 评论点赞 toggle */
    LikeToggleVO toggleCommentLike(Long commentId);

    /** 帖子点赞状态 + 计数查询 */
    LikeToggleVO getPostLikeStatus(String postCode);

    /** 帖子点赞总数（Redis 优先） */
    Long getPostLikeCount(String postCode);

    /**
     * 列表批量填充 isLiked（供 PostController 列表组装调用，11 §13 末段）。
     * @param postIds 帖子内部 id 集合
     * @param loginId 当前用户内部 id
     * @return postId → 是否赞过
     */
    Map<Long, Boolean> batchPostLiked(Collection<Long> postIds, Long loginId);

    /**
     * 列表批量填充评论 isLiked（供 post 服务评论列表装配调用，Phase 5 新增）。
     *
     * <p>与 {@link #batchPostLiked} 同构：Redis pipeline 一次往返，Redis 不可用时降级 DB 逐条比对。</p>
     *
     * @param commentIds 评论内部 id 集合
     * @param loginId    当前用户内部 id
     * @return commentId → 是否赞过（缺失默认 false）
     */
    Map<Long, Boolean> batchCommentLiked(Collection<Long> commentIds, Long loginId);
}