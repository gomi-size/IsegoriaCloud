package com.ruwei.social.service.Impl;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.UserFollowOrFansPageDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.entity.UserFollow;
import com.ruwei.model.vo.UserVO;
import com.ruwei.social.event.FollowEvent;
import com.ruwei.social.manager.FollowCacheManager;
import com.ruwei.social.mapper.UserFollowMapper;
import com.ruwei.social.service.UserFollowService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
* @author Administrator
* @description 针对表【user_follow(关注关系表)】的数据库操作Service实现
* @createDate 2026-07-29 17:56:04
*
* <p><b>ID 体系约定：</b>本表 followerId / followeeId 统一存储用户的<b>内部主键 id</b>
* （即 Sa-Token 的 loginId，雪花 ASSIGN_ID），<u>不再</u>使用对外编码 userId。
* 对外接口同时接受 id 或 userId 入参，先经 {@link #resolveTargetInternalId(Long, Long)} 解析为内部 id 再落库，
* 这样既能兼容前端两种传参，又能让后续 WS / MQ 推送直接按内部 id 路由，避免两套 ID 混用。</p>
*/
@Service
public class UserFollowServiceImpl extends ServiceImpl<UserFollowMapper, UserFollow>
    implements UserFollowService {

    @DubboReference
    private InnerUserService innerUserService;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private FollowCacheManager followCacheManager;

    /**
     * 将入参解析为目标用户的内部主键 id。
     * <ul>
     *   <li>id 非空 → 视为内部主键，直接校验存在性后返回；</li>
     *   <li>否则 userId 非空 → 按对外编码查表，返回其内部 id；</li>
     *   <li>两者皆空 → 参数错误。</li>
     * </ul>
     * @param id     内部主键（优先）
     * @param userId 对外编码
     * @return 目标用户的内部 id
     */
    private Long resolveTargetInternalId(Long id, Long userId) {
        if (id != null) {
            User u = innerUserService.getById(id);
            ThrowUtils.throwIf(BeanUtil.isEmpty(u), ErrorCode.NOT_FOUND_ERROR, "用户不存在");
            return id;
        }
        if (userId != null) {
            User u = innerUserService.getByUserId(userId);
            ThrowUtils.throwIf(BeanUtil.isEmpty(u), ErrorCode.NOT_FOUND_ERROR, "用户不存在");
            return u.getId();
        }
        ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, "请传入 id 或 userId");
        return null;
    }

    /**
     * 关注用户（幂等：已关注时再次调用视为重复操作并提示）。
     * 库内统一以内部 id 记录关系。
     * @param id     对方内部主键（与 userId 二选一）
     * @param userId 对方对外编码（与 id 二选一）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void followUser(Long id, Long userId) {
        // 当前登录用户的内部 id（= Sa-Token loginId = user_follow 存储键）
        long loginId = StpUtil.getLoginIdAsLong();

        // 解析对方内部 id（入参 id / userId 二选一）
        Long targetId = resolveTargetInternalId(id, userId);

        // 不能关注自己
        ThrowUtils.throwIf(loginId == targetId, ErrorCode.OPERATION_ERROR, "无法关注自己");

        // 查已有记录（均以内部 id 为键）
        UserFollow one = lambdaQuery().eq(UserFollow::getFollowerId, loginId)
                .eq(UserFollow::getFolloweeId, targetId)
                .one();

        if (BeanUtil.isEmpty(one)) {
            // 新增关注
            UserFollow userFollow = new UserFollow();
            userFollow.setFollowerId(loginId);
            userFollow.setFolloweeId(targetId);
            userFollow.setStatus(1);
            userFollow.setCreatedAt(new Date());
            userFollow.setUpdatedAt(new Date());

            boolean save = save(userFollow);
            ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR, "关注失败");

            // 我的关注数 +1
            innerUserService.incrementUserCount(loginId, "followCount", 1);


            // 对方粉丝数 +1（按内部 id 匹配，避免内外 id 混用）
            innerUserService.incrementUserCount(targetId, "fansCount", 1);


            // 互粉：若对方也已关注我，则我的粉丝数也 +1
            if (isMutualFollow(loginId, targetId)) {
                innerUserService.incrementUserCount(loginId, "fansCount", 1);
            }
            //推送消息
            eventPublisher.publishEvent(new FollowEvent(this,loginId,targetId,FollowEvent.ACTION_FOLLOW));

        } else if (one.getStatus() == 2) {
            // 曾关注但已取消，恢复关注
            boolean update = lambdaUpdate().eq(UserFollow::getFollowerId, loginId)
                    .eq(UserFollow::getFolloweeId, targetId)
                    .set(UserFollow::getStatus, 1)
                    .set(UserFollow::getUpdatedAt, new Date())
                    .update();
            ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "关注失败");

            innerUserService.incrementUserCount(loginId, "followCount", 1);
            innerUserService.incrementUserCount(targetId, "fansCount", 1);

            if (isMutualFollow(loginId, targetId)) {
                innerUserService.incrementUserCount(loginId, "fansCount", 1);
            }
            //推送消息
            eventPublisher.publishEvent(new FollowEvent(this, loginId, targetId, FollowEvent.ACTION_FOLLOW));
        } else {
            ThrowUtils.throwIf(true, ErrorCode.OPERATION_ERROR, "无法重复关注");
        }
    }

    /**
     * 取消关注用户。
     * @param id     对方内部主键（与 userId 二选一）
     * @param userId 对方对外编码（与 id 二选一）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelFollowUser(Long id, Long userId) {
        long loginId = StpUtil.getLoginIdAsLong();
        Long targetId = resolveTargetInternalId(id, userId);

        ThrowUtils.throwIf(loginId == targetId, ErrorCode.OPERATION_ERROR, "无法取消关注自己");

        UserFollow one = lambdaQuery().eq(UserFollow::getFollowerId, loginId)
                .eq(UserFollow::getFolloweeId, targetId)
                .one();
        ThrowUtils.throwIf(BeanUtil.isEmpty(one), ErrorCode.NOT_FOUND_ERROR, "没有关注信息，请刷新页面");

        if (one.getStatus() == 1) {
            boolean update = lambdaUpdate().eq(UserFollow::getFollowerId, loginId)
                    .eq(UserFollow::getFolloweeId, targetId)
                    .set(UserFollow::getStatus, 2)
                    .set(UserFollow::getUpdatedAt, new Date())
                    .update();
            ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "取消关注失败");

            // 我的关注数 -1
            innerUserService.incrementUserCount(loginId, "followCount", -1);
            // 对方粉丝数 -1
            innerUserService.incrementUserCount(targetId, "fansCount", -1);

            // 互粉解除：若对方已关注我，则我的粉丝数也 -1
            if (isMutualFollow(loginId, targetId)) {
                innerUserService.incrementUserCount(loginId, "fansCount", -1);
            }
            eventPublisher.publishEvent(new FollowEvent(this, loginId, targetId, FollowEvent.ACTION_CANCEL));

        } else if (one.getStatus() == 2) {
            ThrowUtils.throwIf(true, ErrorCode.OPERATION_ERROR, "已经处于取消关注状态");
        }
    }








    /**
     * 判断 targetId 是否已关注 loginId（互粉检测，仅统计 status=1）。
     * 两者均为内部 id。
     * @param loginId   当前用户内部 id
     * @param targetId  对方内部 id
     * @return 是否互相关注
     */
    private boolean isMutualFollow(Long loginId, Long targetId) {
        return lambdaQuery()
                .eq(UserFollow::getFollowerId, targetId)
                .eq(UserFollow::getFolloweeId, loginId)
                .eq(UserFollow::getStatus, 1)
                .exists();
    }

    /**
     * 获取我关注的用户列表（分页，数据来自 Redis 热索引）。
     * <p>实现要点：
     * <ol>
     *   <li>从 {@code uf:following:{loginId}} 取「我关注的人」id 集合（键缺失自动回源 user_follow 表重建）；</li>
     *   <li>Set 无序，转 List 后按 id 倒序排序保证分页结果稳定可预期（不再是关注时间倒序）；</li>
     *   <li>内存分页后批量查 {@code user} 表组装 VO。</li>
     * </ol>
     * 全部使用内部 id。
     *
     * @param dto 分页参数（current / pageSize）
     * @return 关注用户的分页结果（UserVO）
     */
    @Override
    public IPage<UserVO> getFollowUserList(UserFollowOrFansPageDTO dto) {
        long loginId = StpUtil.getLoginIdAsLong();

        // 1) 从 Redis 热索引取「我关注的人」id 集合（键缺失自动回源重建）
        Set<String> idSet = followCacheManager.getFollower(loginId);

        // 2) 转 Long 并按 id 倒序（Set 无序，排序保证分页结果稳定可预期）
        List<Long> followeeIds = idSet.stream()
                .map(Long::valueOf)
                .sorted(Comparator.reverseOrder())
                .toList();

        // 3) 内存分页
        long total = followeeIds.size();
        int from = (int) Math.min((dto.getCurrent() - 1) * dto.getPageSize(), total);
        int to = (int) Math.min(from + dto.getPageSize(), total);
        List<Long> pageIds = followeeIds.subList(from, to);

        // 4) 批量查本页用户详情并组装 VO
        List<UserVO> voList = buildUserVOList(pageIds);

        // 5) 组装分页结果
        IPage<UserVO> result = new Page<>(dto.getCurrent(), dto.getPageSize(), total);
        result.setRecords(voList);
        return result;
    }

    /**
     * 获取我的粉丝列表（分页，数据来自 Redis 热索引）。
     * <p>实现要点与 {@link #getFollowUserList} 一致：从 {@code uf:followers:{loginId}} 取粉丝 id 集合
     * （键缺失自动回源重建），转 List 按 id 倒序后内存分页，再批量查用户组装 VO。全部使用内部 id。</p>
     *
     * @param dto 分页参数（current / pageSize）
     * @return 粉丝用户的分页结果（UserVO）
     */
    @Override
    public IPage<UserVO> getFansUserList(UserFollowOrFansPageDTO dto) {
        long loginId = StpUtil.getLoginIdAsLong();

        // 1) 从 Redis 热索引取粉丝 id 集合（键缺失自动回源重建）
        Set<String> idSet = followCacheManager.getFollowers(loginId);

        // 2) 转 Long 并按 id 倒序（Set 无序，排序保证分页结果稳定可预期）
        List<Long> followerIds = idSet.stream()
                .map(Long::valueOf)
                .sorted(Comparator.reverseOrder())
                .toList();

        // 3) 内存分页
        long total = followerIds.size();
        int from = (int) Math.min((dto.getCurrent() - 1) * dto.getPageSize(), total);
        int to = (int) Math.min(from + dto.getPageSize(), total);
        List<Long> pageIds = followerIds.subList(from, to);

        // 4) 批量查本页用户详情并组装 VO
        List<UserVO> voList = buildUserVOList(pageIds);

        // 5) 组装分页结果
        IPage<UserVO> result = new Page<>(dto.getCurrent(), dto.getPageSize(), total);
        result.setRecords(voList);
        return result;
    }

    /**
     * 按内部 id 集合批量查询用户并组装 UserVO（保持入参顺序，过滤已注销/已删除用户）。
     */
    private List<UserVO> buildUserVOList(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<User> users = innerUserService.listByIds(ids);
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        return ids.stream()
                .map(userMap::get)
                .filter(Objects::nonNull)
                .map(u -> BeanUtil.copyProperties(u, UserVO.class))
                .toList();
    }

    /**
     * 当前登录用户是否关注了目标用户（读 Redis 热索引，键缺失自动回源重建）。
     */
    @Override
    public boolean isFollowed(Long id, Long userId) {
        long loginId = StpUtil.getLoginIdAsLong();
        Long targetId = resolveTargetInternalId(id, userId);
        return Boolean.TRUE.equals(followCacheManager.isFollowing(loginId, targetId));
    }

    /**
     * 目标用户是否关注了当前登录用户（即目标用户是不是我的粉丝）。
     */
    @Override
    public boolean isFans(Long id, Long userId) {
        long loginId = StpUtil.getLoginIdAsLong();
        Long targetId = resolveTargetInternalId(id, userId);
        // targetId 是否关注了 loginId
        return Boolean.TRUE.equals(followCacheManager.isFollower(loginId, targetId));
    }

    /**
     * 与目标用户是否互相关注（= 我关注了他 且 他关注了我）。
     */
    @Override
    public boolean isMutual(Long id, Long userId) {
        long loginId = StpUtil.getLoginIdAsLong();
        Long targetId = resolveTargetInternalId(id, userId);
        return Boolean.TRUE.equals(followCacheManager.isFollowing(loginId, targetId))
                && Boolean.TRUE.equals(followCacheManager.isFollower(loginId, targetId));
    }
}
