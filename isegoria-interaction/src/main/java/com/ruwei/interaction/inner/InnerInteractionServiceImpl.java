package com.ruwei.interaction.inner;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ruwei.innerservice.InnerInteractionService;
import com.ruwei.interaction.service.CollectService;
import com.ruwei.interaction.service.LikeService;
import com.ruwei.model.entity.PostCollect;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 互动契约的 Dubbo provider（供 post / rec 远程调用）。
 *
 * <p>全部为按 id 集合的幂等读；<b>不校验调用方登录态</b>，loginId 由调用方显式传入
 * （Dubbo provider 线程无 HTTP 上下文，StpUtil 不可用）。</p>
 *
 * <p>post 侧已在调用的 5 个方法必须全部实现，否则 post 的列表/评论接口会
 * {@code No provider available}：见 {@code PostServiceImpl:1152/1664/1665/1717}、
 * {@code CommentServiceImpl:301/365}。</p>
 *
 * @author ruwei
 */
@DubboService
public class InnerInteractionServiceImpl implements InnerInteractionService {

    @Resource
    private LikeService likeService;

    @Resource
    private CollectService collectService;

    @Override
    public Map<Long, Boolean> getPostLikedMap(Long userId, List<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return likeService.batchPostLiked(postIds, userId);
    }

    @Override
    public Map<Long, Boolean> getPostCollectedMap(Long userId, List<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return collectService.batchIsCollected(postIds, userId);
    }

    @Override
    public Map<Long, Boolean> getCommentLikedMap(Long userId, List<Long> commentIds) {
        if (userId == null || commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return likeService.batchCommentLiked(commentIds, userId);
    }

    @Override
    public List<PostCollect> pageMyCollectRelations(Long userId, long current, long pageSize) {
        if (userId == null) {
            return Collections.emptyList();
        }
        IPage<PostCollect> page = collectService.pageMyCollect(userId, current, pageSize);
        return page == null || page.getRecords() == null
                ? Collections.emptyList()
                : page.getRecords();
    }

    @Override
    public long countMyCollect(Long userId) {
        if (userId == null) {
            return 0L;
        }
        return collectService.countMyCollect(userId);
    }
}