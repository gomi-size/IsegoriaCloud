package com.ruwei.interaction.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.entity.PostCollect;
import com.ruwei.model.vo.CollectToggleVO;

import java.util.Collection;
import java.util.Map;

/**
 * 帖子收藏服务（物理删 toggle，DB 直写）。
 *
 * <p>收藏是低频私密行为，不走点赞那套 Redis 先行 + MQ 落库：
 * 写路径 DB 直写（insert/delete）+ 原子计数；读路径一次 IN 查询批量填充「是否已收藏」。
 * 收藏不通知作者（type=7 预留暂不启用）。</p>
 *
 * @author ruwei
 */
public interface CollectService extends IService<PostCollect> {

    /**
     * 收藏/取消收藏 toggle（无状态翻转）。
     *
     * @param postId 帖子内部 id
     * @return 切换后状态 {isCollected, collectCount}
     */
    CollectToggleVO toggle(Long postId);

    /**
     * 批量填充「是否已收藏」（供列表装配，一次 IN 查，无 N+1）。
     *
     * @param postIds 帖子内部 id 集合
     * @param loginId 当前用户内部 id
     * @return postId → 是否已收藏（缺失默认 false）
     */
    Map<Long, Boolean> batchIsCollected(Collection<Long> postIds, Long loginId);

    /**
     * 分页查询我的收藏关系（按收藏时间倒序，folderId=0 默认收藏夹）。
     *
     * @param loginId  当前用户内部 id
     * @param current  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 收藏关系分页结果
     */
    IPage<PostCollect> pageMyCollect(Long loginId, long current, long pageSize);

    /**
     * 统计我的收藏关系总数（{@link #pageMyCollect} 的 total；供 {@code /collect/list} 与
     * Dubbo 契约 {@code InnerInteractionService.countMyCollect} 复用）。
     *
     * @param loginId 当前用户内部 id
     * @return 收藏总数
     */
    long countMyCollect(Long loginId);
}