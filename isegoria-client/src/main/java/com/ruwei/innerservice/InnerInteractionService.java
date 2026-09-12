package com.ruwei.innerservice;

import java.util.List;
import java.util.Map;

/** interaction 服务提供（InnerInteractionServiceImpl @DubboService） */
public interface InnerInteractionService {

    /**
     * 批量查询是否关注帖子
     * @param userId
     * @param postIds
     * @return
     */
    Map<Long, Boolean> getPostLikedMap(Long userId, List<Long> postIds);

    /**
     * 批量查询是否收藏帖子
     * @param userId
     * @param postIds
     * @return
     */
    Map<Long, Boolean> getPostCollectedMap(Long userId, List<Long> postIds);

    /**
     * 批量是否点赞评论
     * @param userId
     * @param commentIds
     * @return
     */
    Map<Long, Boolean> getCommentLikedMap(Long userId, List<Long> commentIds);
}
