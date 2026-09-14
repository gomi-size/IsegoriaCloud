package com.ruwei.interaction.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruwei.interaction.manager.LikeCacheManager;
import com.ruwei.interaction.mapper.PostLikeMapper;
import com.ruwei.model.entity.PostLike;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 点赞计数对账：每 5 分钟校准 Redis 计数与 DB 行数。
 *
 * <p>以 {@code post_like} 真实行数为最终真相；dirty 集合驱动（RENAME 原子领取），空跑成本≈0。
 * 只对账帖子点赞（评论点赞沿用旧实现范围）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class LikeReconcileJob {

    @Resource
    private LikeCacheManager likeCacheManager;
    @Resource
    private PostLikeMapper postLikeMapper;

    @Scheduled(cron = "0 */5 * * * *")
    public void reconcile() {
        Set<Long> dirty = likeCacheManager.takeDirtyPosts();
        if (dirty.isEmpty()) {
            return;
        }
        int fixed = 0;
        for (Long postId : dirty) {
            Long dbReal = postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                    .eq(PostLike::getPostId, postId));
            long redisReal = likeCacheManager.getPostLikeCount(postId);
            if (redisReal != dbReal) {
                likeCacheManager.setPostCount(postId, dbReal);
                fixed++;
                log.info("点赞对账校正 postId={} redis={} dbReal={}", postId, redisReal, dbReal);
            }
        }
        if (fixed > 0) {
            log.warn("点赞对账本批校正 {} 条（阈值告警 100）", fixed);
        }
    }
}