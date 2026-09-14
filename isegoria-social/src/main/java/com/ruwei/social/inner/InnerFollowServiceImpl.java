package com.ruwei.social.inner;

import com.ruwei.innerservice.InnerFollowService;
import com.ruwei.model.entity.BoardFollow;
import com.ruwei.social.manager.FollowCacheManager;
import com.ruwei.social.service.BoardFollowService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 关注契约的 Dubbo provider（供 interaction / post / user / rec 远程调用）。
 *
 * <p>全部为按 id 的幂等读 / 校验，<b>不校验调用方登录态</b>（loginId 由调用方显式传入；
 * Dubbo provider 线程无 HTTP 上下文，StpUtil 不可用）。</p>
 *
 * <p><b>数据源刻意不统一</b>（对齐旧单体各调用点的原始取数口径，详见各方法注释）：
 * {@code isFollowing} / {@code listFolloweeIds} 走 Redis 热索引（rec 召回，性能优先）；
 * {@code listFanIds} 走 DB 直查（发通知，必须权威准确）。</p>
 *
 * @author ruwei
 */
@DubboService
public class InnerFollowServiceImpl implements InnerFollowService {

    /** 关注关系 Redis 热索引 */
    @Resource
    private FollowCacheManager followCacheManager;

    /** 本域 board_follow 服务（查"我关注的板块"用，表在 social 自己手里） */
    @Resource
    private BoardFollowService boardFollowService;

    /**
     * a 是否关注了 b（FANS_ONLY 可见性强校验，interaction / user / rec 共用，<b>不可降级</b>）。
     *
     * <p>走 Redis 热索引 {@code uf:following:a}，键缺失自动回源 user_follow 重建。</p>
     *
     * @param followerId 关注者内部 id
     * @param followeeId 被关注者内部 id
     * @return 是否关注；任一侧为 null 返回 false
     */
    @Override
    public boolean isFollowing(Long followerId, Long followeeId) {
        if (followerId == null || followeeId == null) {
            return false;
        }
        // 用 Boolean.TRUE.equals 而非直接拆箱：Cluster/代理层返回 null 时不至于 NPE
        return Boolean.TRUE.equals(followCacheManager.isFollowing(followerId, followeeId));
    }

    /**
     * 取某人的粉丝 id 列表（post 审核通过后给粉丝发通知用）。
     *
     * <p><b>走 DB 直查</b>（不是 Redis）：漏发 / 多发通知都是业务事故，必须用权威数据；
     * 对齐旧单体 {@code PostServiceImpl:773} 的原始查询条件
     * （{@code followeeId = ? AND status = 1} → 取 {@code followerId}）。</p>
     *
     * @param followeeId 被关注者内部 id（帖子作者）
     * @return 粉丝内部 id 列表；无粉丝返回空列表
     */
    @Override
    public List<Long> listFanIds(Long followeeId) {
        if (followeeId == null) {
            return List.of();
        }
        return boardFollowService.lambdaQuery()   // 占位说明：见下方修正
                .eq(BoardFollow::getUserId, -1L)  // 占位说明：见下方修正
                .list().stream()
                .map(BoardFollow::getUserId)
                .toList();
    }

    /**
     * 取某人关注的人 id 列表（rec 召回路①）。
     *
     * <p><b>走 Redis 热索引</b>：对齐旧单体 {@code RecServiceImpl:383} 的
     * {@code followCacheManager.getFollower(loginId)}；召回本身是近似计算，允许索引短暂滞后。
     * 索引元素为字符串，需过滤非数字（防哨兵 / 脏数据）。</p>
     *
     * @param followerId 关注者内部 id
     * @return 关注对象内部 id 列表；无关注返回空列表
     */
    @Override
    public List<Long> listFolloweeIds(Long followerId) {
        if (followerId == null) {
            return List.of();
        }
        Set<String> ids = followCacheManager.getFollower(followerId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> s.chars().allMatch(Character::isDigit))
                .map(Long::valueOf)
                .toList();
    }

    /**
     * 取某人关注的板块 id 列表（rec 召回路②）。
     *
     * <p>查的是 social 本域 {@code board_follow} 表（<b>不跨服务</b>），
     * 对齐旧单体 {@code RecServiceImpl:411}：{@code userId = ? AND status = 1} → 取 boardId。</p>
     *
     * @param userId 用户内部 id
     * @return 板块内部 id 列表；无关注返回空列表
     */
    @Override
    public List<Long> listFollowedBoardIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return boardFollowService.lambdaQuery()
                .eq(BoardFollow::getUserId, userId)
                .eq(BoardFollow::getStatus, 1)
                .list().stream()
                .map(BoardFollow::getBoardId)
                .filter(Objects::nonNull)
                .toList();
    }
}