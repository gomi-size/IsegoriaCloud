package com.ruwei.interaction.service.Impl;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerFollowService;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.interaction.manager.LikeCacheManager;
import com.ruwei.interaction.mapper.CommentLikeMapper;
import com.ruwei.interaction.mapper.PostLikeMapper;
import com.ruwei.interaction.mq.LikeCorrelationData;
import com.ruwei.interaction.mq.LikeMqConfig;
import com.ruwei.interaction.service.LikeService;
import com.ruwei.model.dto.LikePersistMessage;
import com.ruwei.model.entity.*;
import com.ruwei.model.enums.PostAuditStatusEnum;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;
import com.ruwei.model.mq.LikeEventMessage;
import com.ruwei.model.mq.PostIndexMessage;
import com.ruwei.model.vo.LikeToggleVO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 点赞业务实现（实施指南 13-like-module-impl.md §5.4，设计依据 docs/modules/11-like-module.md）。
 *
 * <p>核心链路：<b>Redis 先行（Lua 原子 toggle）→ MQ 异步落库（带真实 action）→ evt.like 通知</b>，
 * 与 {@link LikeCacheManager}（Redis 热索引 + 原子翻转）、LikePersistConsumer（MQ 消费者幂等落库）
 * 分层协作：
 * <ul>
 *   <li><b>写路径（toggle）</b>：帖子/评论存在性 + 状态 + 可见性校验 →
 *       {@link LikeCacheManager#togglePostLike} Lua 原子翻转（关系 Set 增删 + 计数 INCR/DECR 同脚本完成）→
 *       {@link #sendMq} 异步落库 → 点赞且非自赞时发 {@link LikeEventMessage}
 *       （forum.exchange / evt.like：notify 收站内通知、rec 收兴趣画像）；</li>
 *   <li><b>读路径</b>：是否赞过 / 点赞计数一律取自 Redis（键缺失由 LikeCacheManager 回源 DB 懒加载重建，
 *       空集哨兵防空穿透）；列表批量走 pipeline 一次往返（11 §9.3）；</li>
 *   <li><b>降级（11 §12）</b>：{@code like.fallback-to-db=true} 时全链路走 DB；Redis 不可用自动降纯 DB 路径；
 *       MQ 发送异常 / confirm 失败时同步直写 DB，保证功能不挂。</li>
 * </ul>
 *
 * <h3>跨服务改造说明（Phase 5）</h3>
 * <ul>
 *   <li>旧单体直注 {@code PostService} / {@code CommentService} / {@code FollowCacheManager}
 *       → 改为 {@link InnerPostService} / {@link InnerFollowService}（{@code @DubboReference}）：
 *       post / comment 表归 post 服务，interaction 既不直查也不直改。</li>
 *   <li>旧单体 {@code CountUtils.increment(postService/commentService, ...)} 依赖本 JVM 的
 *       {@code IService}（跨服务拿不到）→ 改为 {@code innerPostService.incrementPostCount(...)} /
 *       {@code incrementCommentCount(...)}（远端 DB 层原子计数，列名走 provider 白名单）。</li>
 *   <li>旧单体本地事件 {@code LikeEvent} / {@code PostIndexEvent}（{@code ApplicationEvent}
 *       不可跨 JVM）→ 改为 MQ 消息 {@link LikeEventMessage} / {@link PostIndexMessage}。</li>
 *   <li>{@link LikeCorrelationData} 不再被本类之外引用，但仍放在 {@code interaction.mq} 包
 *       （它继承 Spring AMQP 的 {@code CorrelationData}，不能放进纯 POJO 的 isegoria-model）。</li>
 * </ul>
 *
 * <p>约定：点赞者 id 一律服务端取（StpUtil），不信任前端；Redis 键与集合元素一律内部主键（与 post_like /
 * 通知模块口径统一，防串号）；计数增减统一走远端原子 SQL，杜绝读改写竞态。</p>
 *
 * @author ruwei
 */
@Slf4j
@Service
public class LikeServiceImpl implements LikeService {

    /** post 服务（Dubbo）：postCode→Post 解析、帖子/评论存在性校验、冗余计数原子增减 */
    @DubboReference
    private InnerPostService innerPostService;

    /** social 服务（Dubbo）：FANS_ONLY 可见性强校验（不可降级） */
    @DubboReference
    private InnerFollowService innerFollowService;

    /** 点赞 Redis 热索引 + Lua 原子 toggle 管理器（关系 Set + 计数键） */
    @Resource
    private LikeCacheManager likeCacheManager;

    /** 帖子点赞关系表（DB 最终真相，MQ 消费者落库 / 降级直写 / 回源重建） */
    @Resource
    private PostLikeMapper postLikeMapper;

    /** 评论点赞关系表（DB 最终真相，降级直写 / 回源重建） */
    @Resource
    private CommentLikeMapper commentLikeMapper;

    /** RabbitTemplate：发 like.exchange（异步落库）+ forum.exchange（evt.like / es.post.index） */
    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 降级总开关：true 时全链路走 DB（11 §12 降级开关） */
    @Value("${like.fallback-to-db:false}")
    private boolean fallbackToDb;

    /**
     * 注册 RabbitMQ publisher-confirm 回调（{@code @PostConstruct}，应用启动时执行一次）。
     *
     * <p>confirm 失败（{@code ack=false}，broker 拒收 / 路由失败）时，从自定义 {@link LikeCorrelationData}
     * 中取出发送前携带的原始 {@link LikePersistMessage}，走 {@link #directPersist} 同步直写 DB 兜底
     * （11 §9.1 ⑥）。不使用 {@code CorrelationData.getReturned()}：它只返回 Spring AMQP 的
     * {@code ReturnedMessage} 包装，还原不了原始业务对象。</p>
     *
     * <p>前提：{@code application.yml} 已配 {@code spring.rabbitmq.publisher-confirm-type: correlated}，
     * 否则回调不会被触发。</p>
     */
    @PostConstruct
    public void initConfirmCallback() {
        // MQ confirm 失败 → 异步 best-effort 降级直写 DB（11 §9.1 ⑥）
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack && correlationData != null) {
                if (correlationData instanceof LikeCorrelationData likeData) {
                    // 直接取出原始的 Java 对象
                    LikePersistMessage msg = likeData.getMessage();
                    log.warn("点赞 MQ 确认失败，降级直写 DB eventId={} cause={}", msg.getEventId(), cause);
                    directPersist(msg);
                }
            }
        });
    }

    // ===================== 帖子点赞 =====================

    /**
     * 帖子点赞 toggle（11 §9.1 ①~⑧）。无状态翻转：前端不传 action，后端查当前状态并取反。
     *
     * <p>流程：postCode → postId 解析（Dubbo 调 post）→ 帖子存在 + PUBLISHED + APPROVED 校验 →
     * 可见性校验（PRIVATE 仅作者 / FANS_ONLY 必须已关注，Dubbo 调 social）→
     * Redis Lua 原子 toggle → {@link #sendMq} 异步落库（带真实 action）→
     * action=1 且非自赞发 {@link LikeEventMessage} → 返回 {isLiked, likeCount}（均来自 Redis）。</p>
     *
     * <p>降级：{@link #fallbackToDb} 开启或 Redis 不可用（{@link RedisConnectionFailureException}）时，
     * 走 {@link #directTogglePostDb} 纯 DB 路径（11 §12），功能不挂。</p>
     *
     * @param postCode 帖子业务编码（对外 String，内部解析为 postId）
     * @return 切换后状态：isLiked 是否已赞、likeCount 最新计数
     * @throws com.ruwei.common.core.BusinessException 帖子不存在（NOT_FOUND_ERROR）/
     *                           未发布或未过审（OPERATION_ERROR）/ 可见性不满足（NO_AUTH_ERROR）时抛出
     */
    @Override
    public LikeToggleVO togglePostLike(String postCode) {
        long loginId = StpUtil.getLoginIdAsLong();
        Post post = innerPostService.getByPostCode(postCode);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // 只有「已发布 + 审核通过」可点赞（11 §9.1 ②）
        ThrowUtils.throwIf(!PostStatusEnum.PUBLISHED.matches(post.getStatus())
                        || !PostAuditStatusEnum.APPROVED.matches(post.getAuditStatus()),
                ErrorCode.OPERATION_ERROR, "该帖子当前不可点赞");
        // 可见性（11 §9.1 ③）
        checkVisibility(post, loginId);
        Long postId = post.getId();
        if (fallbackToDb) {
            return directTogglePostDb(postId, loginId, post.getUserId());
        }
        try {
            // 进行点赞（Redis 原子 toggle）
            LikeCacheManager.ToggleResult r = likeCacheManager.togglePostLike(postId, loginId);
            sendMq(1, postId, loginId, r.action());
            // 点赞成功且非本人 → 发 evt.like（notify 发通知、rec 记兴趣画像，一条消息进两个队列）
            if (r.action() == 1 && !Objects.equals(loginId, post.getUserId())) {
                publishLikeEvent(loginId, postId, post.getUserId());
            }
            return new LikeToggleVO(r.action() == 1, r.count());
        } catch (RedisConnectionFailureException ex) {
            log.warn("Redis 不可用，降级纯 DB 路径 postId={}", postId);
            return directTogglePostDb(postId, loginId, post.getUserId());
        }
    }

    // ===================== 评论点赞 =====================

    /**
     * 评论点赞 toggle（11 §9.2）。语义同帖子，仅目标为评论。
     *
     * <p>已删除（status=2）的评论视为不存在，对齐 post 服务 CommentService.deleteComment 口径。
     * 评论点赞本期不发布通知（11 §9.2 ⑥，后期再加）。</p>
     *
     * @param commentId 评论内部主键
     * @return 切换后状态：isLiked / likeCount
     */
    @Override
    public LikeToggleVO toggleCommentLike(Long commentId) {
        Long loginId = StpUtil.getLoginIdAsLong();
        Comment comment = innerPostService.getCommentById(commentId);
        // 不存在或已删除(status=2) 视为不存在（对齐 CommentService.deleteComment 口径）
        ThrowUtils.throwIf(comment == null || Objects.equals(comment.getStatus(), 2),
                ErrorCode.NOT_FOUND_ERROR, "评论不存在");

        try {
            if (fallbackToDb) {
                return directToggleCommentDb(commentId, loginId);
            }
            LikeCacheManager.ToggleResult r = likeCacheManager.toggleCommentLike(commentId, loginId);
            sendMq(2, commentId, loginId, r.action());
            // 评论点赞本期不发通知（后期加）
            return new LikeToggleVO(r.action() == 1, r.count());
        } catch (Exception ex) {
            log.warn("Redis 不可用，降级纯 DB 路径 commentId={}", commentId);
            return directToggleCommentDb(commentId, loginId);
        }
    }

    // ===================== 读：状态 / 计数 =====================

    /**
     * 查询「当前用户是否赞过 + 最新计数」（11 §9.3 读路径）。
     *
     * <p>状态来自 Redis 关系 Set（SISMEMBER），计数来自 Redis 计数键；键缺失时由
     * {@link LikeCacheManager} 回源 DB 懒加载重建，保证读路径不落 DB。</p>
     *
     * @param postCode 帖子业务编码
     * @return {isLiked 是否已赞, likeCount 最新计数}
     */
    @Override
    public LikeToggleVO getPostLikeStatus(String postCode) {
        long loginId = StpUtil.getLoginIdAsLong();
        Post post = innerPostService.getByPostCode(postCode);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        boolean liked = likeCacheManager.isPostLiked(post.getId(), loginId);
        long count = likeCacheManager.getPostLikeCount(post.getId());
        LikeToggleVO vo = new LikeToggleVO();
        vo.setIsLiked(liked);
        vo.setLikeCount(count);
        return vo;
    }

    /**
     * 查询帖子点赞总数（Redis 优先，键缺失回源 DB 重建）。
     *
     * @param postCode 帖子业务编码
     * @return 点赞总数
     */
    @Override
    public Long getPostLikeCount(String postCode) {
        Post post = innerPostService.getByPostCode(postCode);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        return likeCacheManager.getPostLikeCount(post.getId());
    }

    /**
     * 列表批量填充「是否已赞」（11 §9.3 / §13 末段，post 服务列表组装经 Dubbo 调用）。
     *
     * <p>Redis pipeline 一次往返完成，避免 N 次网络开销；Redis 不可用时降级 DB 逐条比对
     * （post_like 查重），功能不挂。</p>
     *
     * @param postIds 帖子内部主键集合（本页列表）
     * @param loginId 当前用户内部主键
     * @return postId → 是否赞过（缺失默认 false）
     */
    @Override
    public Map<Long, Boolean> batchPostLiked(Collection<Long> postIds, Long loginId) {
        if (postIds == null || postIds.isEmpty() || loginId == null) {
            return Map.of();
        }
        try {
            return likeCacheManager.batchIsPostLiked(postIds, loginId);
        } catch (RedisConnectionFailureException ex) {
            log.warn("Redis 不可用，批量 isLiked 降级 DB");
            Map<Long, Boolean> map = new HashMap<>();
            for (Long pid : postIds) {
                boolean liked = postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                        .eq(PostLike::getPostId, pid).eq(PostLike::getUserId, loginId)) > 0;
                map.put(pid, liked);
            }
            return map;
        }
    }

    /**
     * 列表批量填充评论「是否已赞」（Phase 5 新增，post 服务评论列表装配经 Dubbo 调用）。
     *
     * <p>与 {@link #batchPostLiked} 同构：Redis pipeline 一次往返（含空集哨兵回源）；
     * Redis 不可用时降级 DB 逐条比对（comment_like 查重），功能不挂。</p>
     *
     * @param commentIds 评论内部主键集合（本页列表）
     * @param loginId    当前用户内部主键
     * @return commentId → 是否赞过（缺失默认 false）
     */
    @Override
    public Map<Long, Boolean> batchCommentLiked(Collection<Long> commentIds, Long loginId) {
        if (commentIds == null || commentIds.isEmpty() || loginId == null) {
            return Map.of();
        }
        try {
            return likeCacheManager.batchIsCommentLiked(commentIds, loginId);
        } catch (RedisConnectionFailureException ex) {
            log.warn("Redis 不可用，批量评论 isLiked 降级 DB");
            Map<Long, Boolean> map = new HashMap<>();
            for (Long cid : commentIds) {
                boolean liked = commentLikeMapper.selectCount(new LambdaQueryWrapper<CommentLike>()
                        .eq(CommentLike::getCommentId, cid).eq(CommentLike::getUserId, loginId)) > 0;
                map.put(cid, liked);
            }
            return map;
        }
    }

    // ===================== 内部：可见性校验 =====================

    /**
     * 帖子可见性校验（11 §9.1 ③）：PRIVATE 仅作者可点赞；FANS_ONLY 必须关注作者
     * （Dubbo 调 social 服务，跨服务强校验不可降级）；PUBLIC 无需校验。
     *
     * @param post    目标帖子（须含 visibility / userId）
     * @param loginId 当前登录用户内部主键
     */
    private void checkVisibility(Post post, long loginId) {
        Integer visibility = post.getVisibility();
        if (PostVisibilityEnum.PRIVATE.matches(visibility)) {
            // 用 Objects.equals 而非 !=：post.getUserId() 是 Long，!= 会走引用比较（大 id 恒不等）
            ThrowUtils.throwIf(!Objects.equals(post.getUserId(), loginId),
                    ErrorCode.NO_AUTH_ERROR, "私密帖子仅作者可点赞");
        } else if (PostVisibilityEnum.FANS_ONLY.matches(visibility)) {
            boolean following = innerFollowService.isFollowing(loginId, post.getUserId());
            ThrowUtils.throwIf(!following, ErrorCode.NO_AUTH_ERROR, "仅粉丝可点赞该帖子");
        }
        // PUBLIC 无需校验
    }

    // ===================== 内部：MQ 发送（带降级） =====================

    /**
     * 发送点赞异步落库消息（11 §9.1 ⑥）到 like.exchange（rk=like.post / like.comment）。
     *
     * <p>消息携带真实 action（1 赞 / 0 取，来自 Lua 返回值，勿硬编码），eventId 作为 MQ 消费幂等键
     * （消费者 SETNX 去重）。发送异常（broker 不可达等）时同步降级 {@link #directPersist} 直写 DB。
     * CorrelationData 使用自定义 {@link LikeCorrelationData} 携带原始消息，供 confirm 失败回调找回。</p>
     *
     * @param targetType 目标类型：1 帖子 / 2 评论
     * @param targetId   postId 或 commentId（内部主键）
     * @param userId     点赞者内部主键
     * @param action     动作：1 点赞 / 0 取消
     */
    private void sendMq(Integer targetType, Long targetId, Long userId, Integer action) {
        //构建消息体
        LikePersistMessage msg = LikePersistMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .targetType(targetType)
                .targetId(targetId)
                .userId(userId)
                .action(action)
                .timestamp(System.currentTimeMillis())
                .build();
        //发送消息
        try {
            LikeCorrelationData correlationData = new LikeCorrelationData(msg.getEventId(), msg);
            rabbitTemplate.convertAndSend(LikeMqConfig.EXCHANGE,
                    targetType == 1 ? LikeMqConfig.POST_RK : LikeMqConfig.COMMENT_RK,
                    msg,
                    correlationData
            );
            //降级处理
        } catch (Exception e) {
            log.warn("点赞 MQ 发送异常，降级直写 DB targetId={}", targetId, e);
            directPersist(msg);
        }
    }

    /**
     * 发送点赞事件（forum.exchange / evt.like）：notify 发站内通知、rec 记兴趣画像。
     *
     * <p>仅「点赞成功（action=1）且非自赞」时调用；取赞不发（与旧单体一致）。</p>
     *
     * @param actorId    点赞者内部主键
     * @param postId     帖子内部主键
     * @param postUserId 被赞者（帖子作者）内部主键
     */
    private void publishLikeEvent(Long actorId, Long postId, Long postUserId) {
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_EVT_LIKE,
                new LikeEventMessage(actorId, postId, postUserId));
    }

    // ===================== 内部：纯 DB 降级（Redis/MQ 不可用） =====================

    /**
     * 纯 DB 降级路径（11 §12）：Redis/MQ 不可用或 {@link #fallbackToDb} 开启时，
     * 直接对 post_like 表做 toggle。先查关系：不存在则插入并原子 +1 计数（新增且非自赞时同步发
     * {@link LikeEventMessage}）；已存在则删除并原子 -1。likeCount 最终值以 post 表为准回读。
     *
     * @param postId     帖子内部主键
     * @param loginId    当前用户内部主键
     * @param postUserId 帖子作者内部主键（用于自赞判断）
     * @return 切换后状态：isLiked / likeCount
     */
    private LikeToggleVO directTogglePostDb(Long postId, Long loginId, Long postUserId) {
        LikeToggleVO vo = new LikeToggleVO();
        PostLike exist = postLikeMapper.selectOne(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId).eq(PostLike::getUserId, loginId));
        if (exist == null) {
            PostLike record = new PostLike();
            record.setPostId(postId);
            record.setUserId(loginId);
            record.setCreatedAt(new Date());
            postLikeMapper.insert(record);
            innerPostService.incrementPostCount(postId, "likeCount", 1);
            vo.setIsLiked(true);
            // ⚠️ 必须用 Objects.equals：loginId / postUserId 都是 Long，!= 是引用比较，
            //    id 远超 127 缓存上限时恒为 true，会导致「自赞也给自己发通知」
            if (!Objects.equals(loginId, postUserId)) {
                publishLikeEvent(loginId, postId, postUserId);
            }
        } else {
            postLikeMapper.deleteById(exist.getId());
            innerPostService.incrementPostCount(postId, "likeCount", -1);
            vo.setIsLiked(false);
        }
        vo.setLikeCount(readPostLikeCount(postId));
        return vo;
    }

    /**
     * 纯 DB 降级路径（11 §12）：对 comment_like 表做 toggle。评论点赞不发通知。
     *
     * @param commentId 评论内部主键
     * @param loginId   当前用户内部主键
     * @return 切换后状态：isLiked / likeCount
     */
    private LikeToggleVO directToggleCommentDb(Long commentId, Long loginId) {
        LikeToggleVO vo = new LikeToggleVO();
        CommentLike exist = commentLikeMapper.selectOne(new LambdaQueryWrapper<CommentLike>()
                .eq(CommentLike::getCommentId, commentId).eq(CommentLike::getUserId, loginId));
        if (exist == null) {
            CommentLike record = new CommentLike();
            record.setCommentId(commentId);
            record.setUserId(loginId);
            record.setCreatedAt(new Date());
            commentLikeMapper.insert(record);
            innerPostService.incrementCommentCount(commentId, "likeCount", 1);
            vo.setIsLiked(true);
        } else {
            commentLikeMapper.deleteById(exist.getId());
            innerPostService.incrementCommentCount(commentId, "likeCount", -1);
            vo.setIsLiked(false);
        }
        vo.setLikeCount(readCommentLikeCount(commentId));
        return vo;
    }

    /**
     * MQ 兜底落库（11 §9.1 ⑥ / §8.1）：confirm 失败 / 发送异常时同步直写 DB。
     * 仅补「赞」记录（action=1）；取赞降级极少见，由 LikeReconcileJob 对账兜底。
     *
     * <p>幂等防护：先查关系存在性，已存在则跳过；插入成功（影响行数 &gt; 0）才原子 +1 计数，
     * 绝不盲加减，避免与消费者落库重复叠加。</p>
     *
     * <p>帖子点赞真实新增时同步发 {@link PostIndexMessage}（INDEX）请求 rec 重建 ES 索引，
     * 保证主页推荐流（读 ES）的 likeCount 与 DB 一致（与 MQ 消费者路径同一机制）。</p>
     *
     * @param msg 点赞消息体（含 targetType / targetId / userId）
     */
    private void directPersist(LikePersistMessage msg) {
        if (msg.getTargetType() == 1) {
            Long postId = msg.getTargetId();
            Long userId = msg.getUserId();
            PostLike exist = postLikeMapper.selectOne(new LambdaQueryWrapper<PostLike>()
                    .eq(PostLike::getPostId, postId).eq(PostLike::getUserId, userId));
            if (exist == null) {
                PostLike r = new PostLike();
                r.setPostId(postId);
                r.setUserId(userId);
                r.setCreatedAt(new Date());
                if (postLikeMapper.insert(r) > 0) {
                    innerPostService.incrementPostCount(postId, "likeCount", 1);
                    // 降级路径同样同步 ES 索引，避免推荐流计数不一致
                    publishPostIndex(postId);
                }
            }
        } else {
            Long commentId = msg.getTargetId();
            Long userId = msg.getUserId();
            CommentLike exist = commentLikeMapper.selectOne(new LambdaQueryWrapper<CommentLike>()
                    .eq(CommentLike::getCommentId, commentId).eq(CommentLike::getUserId, userId));
            if (exist == null) {
                CommentLike r = new CommentLike();
                r.setCommentId(commentId);
                r.setUserId(userId);
                r.setCreatedAt(new Date());
                if (commentLikeMapper.insert(r) > 0) {
                    innerPostService.incrementCommentCount(commentId, "likeCount", 1);
                }
            }
        }
    }

    // ===================== 内部：小工具 =====================

    /**
     * 回读帖子最新 likeCount（降级路径返回值用）。
     *
     * <p>帖子可能已被删除（Dubbo 返回 null）或计数列为 null，故做空值兜底返回 0，
     * 避免 {@code (long) Long} 拆箱 NPE。</p>
     *
     * @param postId 帖子内部主键
     * @return 帖子 likeCount；查不到返回 0
     */
    private long readPostLikeCount(Long postId) {
        Post latest = innerPostService.getById(postId);
        return (latest == null || latest.getLikeCount() == null) ? 0L : latest.getLikeCount().longValue();
    }

    /**
     * 回读评论最新 likeCount（降级路径返回值用），语义同 {@link #readPostLikeCount(Long)}。
     *
     * @param commentId 评论内部主键
     * @return 评论 likeCount；查不到返回 0
     */
    private long readCommentLikeCount(Long commentId) {
        Comment latest = innerPostService.getCommentById(commentId);
        return (latest == null || latest.getLikeCount() == null) ? 0L : latest.getLikeCount().longValue();
    }

    /**
     * 请求 rec 服务重建帖子 ES 文档（forum.exchange / es.post.index）。
     *
     * <p>点赞计数真实变更后调用，使推荐流（读 ES）的 likeCount 与 DB 一致；
     * 幂等跳过（计数未变）时不得调用，避免无谓重建。</p>
     *
     * @param postId 帖子内部主键
     */
    private void publishPostIndex(Long postId) {
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_POST_INDEX,
                new PostIndexMessage(postId, PostIndexMessage.ACTION_INDEX));
    }
}
