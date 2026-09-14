package com.ruwei.interaction.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.ruwei.interaction.mapper.CommentLikeMapper;
import com.ruwei.interaction.mapper.PostLikeMapper;
import com.ruwei.model.entity.CommentLike;
import com.ruwei.model.entity.PostLike;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 点赞 Redis 热索引与原子 toggle 管理器。
 *
 * <p>职责：为「帖子点赞」与「评论点赞」提供高并发、低延迟的实时展示层。
 * 设计依据 {@code docs/modules/11-like-module.md}（点赞模块定稿，2026-08-18），
 * Redis 先行、DB（{@code post_like} / {@code comment_like}）为最终真相源，二者最终一致。</p>
 *
 * <h3>核心数据（均以内部 id 存储，与 user_follow / 通知模块口径统一）</h3>
 * <ul>
 *   <li>{@code like:post:users:{postId}}        —— Set，赞过该帖的用户内部 id（关系热索引）</li>
 *   <li>{@code like:post:count:{postId}}        —— String，帖子点赞计数（实时展示源）</li>
 *   <li>{@code like:comment:users:{commentId}}  —— Set，赞过该评论的用户内部 id</li>
 *   <li>{@code like:comment:count:{commentId}}  —— String，评论点赞计数</li>
 *   <li>{@code like:dirty:post}                 —— Set，发生过点赞变更的 postId（对账 Job 领取集）</li>
 *   <li>{@code like:dirty:comment}              —— Set，发生过点赞变更的 commentId（对账 Job 领取集）</li>
 * </ul>
 *
 * <h3>关键约定</h3>
 * <ul>
 *   <li><b>读优先 Redis</b>：键缺失时 {@link #ensurePostLoaded} / {@link #ensureCommentLoaded}
 *       回源 DB 重建（懒加载，无需迁移脚本，对齐 {@code FollowCacheManager}）。</li>
 *   <li><b>原子 toggle</b>：点赞/取赞的「关系变更 + 计数增减」由 {@link #TOGGLE_LUA} 在 Redis 内
 *       一次性原子执行，避免「先看未赞→都点赞→计数 +2 但关系只有 1 条」的竞态。</li>
 *   <li><b>空集哨兵</b>：无任何点赞的目标也建键并写入 {@code "-1"}，防止缓存穿透（每次读都回源打 DB）。
 *       所有对外读取集合的方法均依赖哨兵占位，回源路径已自动处理。</li>
 *   <li><b>TTL 7 天自愈</b>：键过期后读路径回源重建即自愈；toggle 时由 Lua 刷新关系/计数键 TTL。</li>
 *   <li><b>脏数据集合</b>：{@code toggle*} 后将目标 id 写入 dirty 集合，由 {@code LikeReconcileJob}
 *       （{@code docs/modules/11-like-module.md} §11.2）定期领取并与 DB 真实行数对账校正。</li>
 * </ul>
 *
 * <p>注意：本类只负责 Redis 侧的数据结构与原子操作，落库（RabbitMQ 消费者）、限频、
 * 通知、可见性校验等由 {@code LikeService} 在上层编排，不在本类职责内。</p>
 *
 */
@Slf4j
@Component
public class LikeCacheManager {

    /** 帖子点赞关系热索引（Set：赞过的用户内部 id） */
    private static final String POST_USERS = "like:post:users:";
    /** 帖子点赞计数（String：实时展示源） */
    private static final String POST_COUNT = "like:post:count:";
    /** 评论点赞关系热索引（Set：赞过的用户内部 id） */
    private static final String COMMENT_USERS = "like:comment:users:";
    /** 评论点赞计数（String：实时展示源） */
    private static final String COMMENT_COUNT = "like:comment:count:";
    /** 脏数据集合：发生过点赞变更的帖子 id（对账 Job 领取，无 TTL） */
    private static final String DIRTY_POST = "like:dirty:post";
    /** 脏数据集合：发生过点赞变更的评论 id（对账 Job 领取，无 TTL） */
    private static final String DIRTY_COMMENT = "like:dirty:comment";
    /** 空集占位哨兵（防空穿透），无点赞的目标也写该值占位，读取时应忽略 */
    private static final String SENTINEL = "-1";
    /** 关系/计数键过期时间：7 天（对齐 FollowCacheManager，过期回源自愈） */
    private static final Duration TTL = Duration.ofDays(7);


    /**
     * toggle 操作的返回结果。
     *
     * @param action 切换后的动作态：{@code 1}=当前为「已赞」，{@code 0}=当前为「已取赞」
     * @param count  切换后的最新点赞计数（来自 Redis 计数器，毫秒级可见）
     */
    public record ToggleResult(int action, long count) {}


    /**
     * 点赞/取赞原子脚本：在同一 Redis 命令内完成「关系变更 + 计数增减 + 入脏集合 + 刷新 TTL」。
     *
     * <p>语义：若用户已是该目标的成员则执行「取赞」（SREM + DECR，action=0），
     * 否则执行「点赞」（SADD + INCR，action=1）。无论哪种都将该目标 id 写入 dirty 集合，
     * 并刷新关系键与计数键 TTL（604800 秒 = 7 天），最后返回 {@code {action, count}}。</p>
     *
     * <p><b>KEYS</b>：{@code [1]=users 关系键, [2]=count 计数键, [3]=dirty 脏集合键}<br>
     * <b>ARGV</b>：{@code [1]=userId 点赞者内部 id, [2]=targetId 帖子/评论内部 id}<br>
     * <b>返回</b>：{@code { action(1赞/0取), count }}</p>
     *
     * <p>必须用 Lua 而非拆分命令的原因：{@code SISMEMBER → SADD/SREM → INCR/DECR} 三步若并发拆开，
     * 会出现「两个请求都看到未赞 → 都点赞 → 计数 +2 但关系仅 1 条」的竞态；Lua 在 Redis 内原子执行可天然规避。
     * 执行本脚本前必须保证计数键已存在且 = DB 真实值（见 {@link #ensurePostLoaded} / {@link #ensureCommentLoaded}）。</p>
     */
    private static final String TOGGLE_LUA =
            "local isMember = redis.call('SISMEMBER', KEYS[1], ARGV[1])\n" +
                    "local action\n" +
                    "if isMember == 1 then\n" +
                    "  redis.call('SREM', KEYS[1], ARGV[1])\n" +
                    "  redis.call('DECR', KEYS[2])\n" +
                    "  action = 0\n" +
                    "else\n" +
                    "  redis.call('SADD', KEYS[1], ARGV[1])\n" +
                    "  redis.call('INCR', KEYS[2])\n" +
                    "  action = 1\n" +
                    "end\n" +
                    "redis.call('SADD', KEYS[3], ARGV[2])\n" +
                    "redis.call('EXPIRE', KEYS[1], 604800)\n" +
                    "redis.call('EXPIRE', KEYS[2], 604800)\n" +
                    "local count = redis.call('GET', KEYS[2])\n" +
                    "return { action, tonumber(count) }\n";

    /** toggle 脚本的 Spring 封装，返回值解析为 {@code List<Long>}：{action, count} */
    private static final DefaultRedisScript<List> TOGGLE_SCRIPT =
            new DefaultRedisScript<>(TOGGLE_LUA, List.class);

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private PostLikeMapper postLikeMapper;
    @Resource
    private CommentLikeMapper commentLikeMapper;


    /**
     * 帖子点赞原子 toggle。
     *
     * <p>流程：先 {@link #ensurePostLoaded} 保证关系/计数键已加载（缺失则回源 DB 重建），
     * 再执行 {@link #TOGGLE_LUA} 完成「点赞/取赞 + 计数 + 脏标记」一次性原子操作。</p>
     *
     * @param postId 帖子内部 id（由上层从对外 postCode 解析而来）
     * @param userId 点赞者内部 id（一律取登录态，不信任前端）
     * @return {@link ToggleResult}，{@code action=1} 表示切换后为「已赞」、{@code 0} 为「已取赞」，count 为最新计数
     */
    public ToggleResult togglePostLike(Long postId, Long userId) {
        // 先确保关系键与计数键已就位（缺失回源 DB），再执行原子脚本
        ensurePostLoaded(postId);
        List<Long> res = redis.execute(TOGGLE_SCRIPT,
                List.of(POST_USERS + postId, POST_COUNT + postId, DIRTY_POST),
                String.valueOf(userId), String.valueOf(postId));

        return new ToggleResult(res.get(0).intValue(), res.get(1).longValue());
    }

    /**
     * 评论点赞原子 toggle（与帖子点赞同构，仅 key 前缀与脏集合不同）。
     *
     * <p>说明：评论点赞本期不发通知（对齐 {@code docs/modules/10-comment-module.md} §6.3，防噪音，二期评估）。</p>
     *
     * @param commentId 评论内部 id（由列表接口下发的内部 id）
     * @param userId    点赞者内部 id（取登录态）
     * @return {@link ToggleResult}，语义同 {@link #togglePostLike}
     */
    public ToggleResult toggleCommentLike(Long commentId, Long userId) {
        ensureCommentLoaded(commentId);
        List<Long> res = redis.execute(TOGGLE_SCRIPT,
                List.of(COMMENT_USERS + commentId, COMMENT_COUNT + commentId, DIRTY_COMMENT),
                String.valueOf(userId), String.valueOf(commentId));
        return new ToggleResult(res.get(0).intValue(), res.get(1).longValue());
    }


    /**
     * 判断当前用户是否赞过该帖子（按钮高亮用）。
     *
     * @param postId 帖子内部 id
     * @param userId 用户内部 id
     * @return 已赞返回 {@code true}，否则 {@code false}（键缺失已自动回源重建）
     */
    public boolean isPostLiked(Long postId, Long userId) {
        ensurePostLoaded(postId);
        return Boolean.TRUE.equals(redis.opsForSet().isMember(POST_USERS + postId, String.valueOf(userId)));
    }

    /**
     * 获取帖子点赞总数（实时展示源）。
     *
     * @param postId 帖子内部 id
     * @return 当前点赞计数；键缺失回源重建后取值，键不存在时返回 {@code 0}
     */
    public long getPostLikeCount(Long postId) {
        ensurePostLoaded(postId);
        String v = redis.opsForValue().get(POST_COUNT + postId);
        return v == null ? 0L : Long.parseLong(v);
    }

    /**
     * 判断当前用户是否赞过该评论（按钮高亮用）。
     *
     * @param commentId 评论内部 id
     * @param userId    用户内部 id
     * @return 已赞返回 {@code true}，否则 {@code false}
     */
    public boolean isCommentLiked(Long commentId, Long userId) {
        ensureCommentLoaded(commentId);
        return Boolean.TRUE.equals(redis.opsForSet().isMember(COMMENT_USERS + commentId, String.valueOf(userId)));
    }

    /**
     * 获取评论点赞总数（实时展示源）。
     *
     * @param commentId 评论内部 id
     * @return 当前点赞计数；键缺失回源重建后取值，键不存在时返回 {@code 0}
     */
    public long getCommentLikeCount(Long commentId) {
        ensureCommentLoaded(commentId);
        String v = redis.opsForValue().get(COMMENT_COUNT + commentId);
        return v == null ? 0L : Long.parseLong(v);
    }

    /**
     * 帖子关系/计数键懒加载回源（防空穿透）。
     *
     * <p>仅当键不存在时回源 {@code post_like} 表重建：
     * <ul>
     *   <li>关系键：用 {@code SELECT userId} 重建赞者集合；无记录则写入 {@code SENTINEL} 占位（防空穿透）。</li>
     *   <li>计数键：以 {@code post_like} 真实行数（{@code SELECT COUNT(*)}）为准，避免与关系集脱节
     *       （对齐 {@code docs/modules/11-like-module.md} §11.2 的「DB 行数为最终真相」原则）。</li>
     * </ul>
     * 计数键在此处即设置 7 天 TTL；关系键 TTL 由后续 toggle 的 Lua 脚本刷新。</p>
     *
     * @param postId 帖子内部 id
     */
    private void ensurePostLoaded(Long postId) {

        String usersKey = POST_USERS + postId;
        String countKey = POST_COUNT + postId;
        String lockKey = "lock:like:rebuild:" + postId;
        if (Boolean.TRUE.equals(redis.hasKey(usersKey)) && Boolean.TRUE.equals(redis.hasKey(countKey))) {
            return;
        }

        Boolean lock = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(2));
        if(Boolean.TRUE.equals(lock)){
            try {
                if (Boolean.FALSE.equals(redis.hasKey(usersKey))) {
                    // 回源重建赞者集合（判空则写哨兵占位，防止穿透）
                    List<Object> userIds = postLikeMapper.selectObjs(new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId, postId).
                            select(PostLike::getUserId));
                    if (userIds.isEmpty()) {
                        // 防空穿透，空值缓存-1
                        redis.opsForSet().add(usersKey, SENTINEL);
                    } else {
                        // 转化为string数组
                        redis.opsForSet().add(usersKey, userIds.stream().map(String::valueOf).toArray(String[]::new));
                    }
                }
                // 计数以真实行数为准
                if (Boolean.FALSE.equals(redis.hasKey(countKey))) {
                    // 以 post_like 真实行数为准（对齐 11 §11.2），避免与关系集脱节
                    long real = postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                            .eq(PostLike::getPostId, postId));
                    redis.opsForValue().set(countKey, String.valueOf(real), TTL);
                }
            } finally {
                redis.delete(lockKey);
            }
        }else {
            try {
                //休眠
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            //再尝试一次，如果能难道就可以那不到就没了
            ensurePostLoaded(postId);
        }


    }


    /**
     * 列表批量 isLiked：一次 pipeline 往返，避免 N 次网络往返。
     *
     * <p>用于帖子列表 / 评论列表的按钮高亮批量填充。先批量确保各键已加载，
     * 再 pipeline 执行 {@code SISMEMBER}，返回顺序与入参 {@code postIds} 一致的映射。</p>
     *
     * @param postIds 待查询的帖子内部 id 集合（已去重的当前页）
     * @param userId  当前用户内部 id
     * @return {@code postId -> 是否赞过} 的有序映射（{@link LinkedHashMap}，保持入参顺序）
     */
    public Map<Long, Boolean> batchIsPostLiked(Collection<Long> postIds, Long userId) {
        if (postIds.isEmpty()) return Collections.emptyMap();
        postIds.forEach(this::ensurePostLoaded);   // 先确保全部键已加载（缺失回源）
        List<Object> hits = redis.executePipelined((RedisCallback<?>) (connection) -> {
            for (Long id : postIds) {
                connection.sIsMember((POST_USERS + id).getBytes(), String.valueOf(userId).getBytes());
            }
            return null;
        });
        Map<Long, Boolean> map = new LinkedHashMap<>();
        int i = 0;
        for (Long id : postIds) {
            map.put(id, Boolean.TRUE.equals(hits.get(i++)));
        }
        return map;
    }

    /**
     * 评论关系/计数键懒加载回源（与 {@link #ensurePostLoaded} 同构）。
     *
     * <p>差异点：评论关系键在回源后此处显式 {@code expire}；计数键以 {@code comment_like} 真实行数重建。
     * 注意：本方法回源时对计数键未单独设置 TTL，依赖后续 toggle 的 Lua 脚本刷新。</p>
     *
     * @param commentId 评论内部 id
     */
    private void ensureCommentLoaded(Long commentId) {
        String usersKey = COMMENT_USERS + commentId;
        String countKey = COMMENT_COUNT + commentId;
        if (!redis.hasKey(usersKey)) {
            List<Object> userIds = commentLikeMapper.selectObjs(new LambdaQueryWrapper<CommentLike>()
                    .eq(CommentLike::getCommentId, commentId).select(CommentLike::getUserId));
            if (userIds.isEmpty()) {
                redis.opsForSet().add(usersKey, SENTINEL);
            } else {
                redis.opsForSet().add(usersKey,
                        userIds.stream().map(String::valueOf).toArray(String[]::new));
            }
            redis.expire(usersKey, TTL);
        }
        if (!redis.hasKey(countKey)) {
            long real = commentLikeMapper.selectCount(new LambdaQueryWrapper<CommentLike>()
                    .eq(CommentLike::getCommentId, commentId));
            redis.opsForValue().set(countKey, String.valueOf(real), TTL);
        }
    }

    /**
     * 领取本批发生点赞变更的帖子 id（对账 Job 调用）。
     *
     * <p><b>原子领取（RENAME 模式）</b>：将原 {@code like:dirty:post} 集合通过 {@code RENAME}
     * 原子重命名为本次处理专属的临时 key（{@code like:dirty:post:processing:{ts}}）。
     * 相比「先 {@code SMEMBERS} 再 {@code DEL}」的两步写法，RENAME 是单条原子命令，
     * 消除了「读取」与「清空」之间的竞态窗口——重命名之后，业务层 Lua toggle 的 {@code SADD}
     * 会自动重建一个全新的 {@code like:dirty:post}，因此本次领取<b>之后</b>新产生的脏数据不会被误删，
     * 自然留给下一轮对账，避免漏对账。</p>
     *
     * <p>流程：① 原子 RENAME 到临时 key（原 key 不存在则 rename 抛异常，视为无脏数据，直接返回空集合）；
     * ② 从临时 key 读取成员；③ 删除临时 key；④ 过滤哨兵占位后转换为 {@code Long} 集合返回。</p>
     *
     * @return 待对账的帖子内部 id 集合；无脏数据时返回空集合
     */
    public Set<Long> takeDirtyPosts() {
        // 1. 生成一个本次处理专属的临时 Key
        String tempKey = DIRTY_POST + ":processing:" + System.currentTimeMillis();

        try {
            // 2. 将原 Key 重命名。此操作是原子的！
            // 业务层接下来的 sadd 会自动创建一个全新的 DIRTY_POST
            redis.rename(DIRTY_POST, tempKey);
        } catch (Exception e) {
            // 如果 DIRTY_POST 不存在，rename 会抛异常，说明没有脏数据
            return Collections.emptySet();
        }

        // 3. 安全地从临时 Key 中读取数据
        Set<String> ids = redis.opsForSet().members(tempKey);
        // 4. 读取完毕，删除临时 Key
        redis.delete(tempKey);

        // 后续的去哨兵和类型转换逻辑不变...
        if (ids == null || ids.isEmpty()) return Collections.emptySet();
        ids.remove(SENTINEL);
        return ids.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /**
     * 领取本批发生点赞变更的评论 id（对账 Job 调用），语义同 {@link #takeDirtyPosts}。
     *
     * <p>同样采用原子 {@code RENAME} 领取：将原 {@code like:dirty:comment} 重命名为
     * {@code like:dirty:comment:processing:{ts}} 临时 key，读取后删除，避免领取过程中
     * 新脏数据被误删而漏对账。</p>
     *
     * @return 待对账的评论内部 id 集合；无脏数据时返回空集合
     */
    public Set<Long> takeDirtyComments() {
        // 1. 生成一个本次处理专属的临时 Key
        String tempKey = DIRTY_COMMENT + ":processing:" + System.currentTimeMillis();

        try {
            // 2. 将原 Key 重命名。此操作是原子的！
            // 业务层接下来的 sadd 会自动创建一个全新的 DIRTY_COMMENT
            redis.rename(DIRTY_COMMENT, tempKey);
        } catch (Exception e) {
            // 如果 DIRTY_COMMENT 不存在，rename 会抛异常，说明没有脏数据
            return Collections.emptySet();
        }

        // 3. 安全地从临时 Key 中读取数据
        Set<String> ids = redis.opsForSet().members(tempKey);
        // 4. 读取完毕，删除临时 Key
        redis.delete(tempKey);

        // 后续的去哨兵和类型转换逻辑不变...
        if (ids == null || ids.isEmpty()) return Collections.emptySet();
        ids.remove(SENTINEL);
        return ids.stream().map(Long::valueOf).collect(Collectors.toSet());
    }


    /**
     * 直接覆盖计数键（对账 Job 校正用）。
     *
     * <p>当 {@code LikeReconcileJob} 发现 Redis 计数与 {@code post_like} 真实行数不一致时，
     * 以 DB 真实行数为准调用本方法覆盖 Redis 计数键（同时刷新 7 天 TTL）。</p>
     *
     * @param postId 帖子内部 id
     * @param count  校正后的点赞计数（应等于 post_like 真实行数）
     */
    public void setPostCount(Long postId, long count) {
        redis.opsForValue().set(POST_COUNT + postId, String.valueOf(count), TTL);
    }

    /**
     * 列表批量 isCommentLiked：一次 pipeline 往返（与 {@link #batchIsPostLiked} 同构）。
     *
     * <p>供 {@code InnerInteractionServiceImpl.getCommentLikedMap} 装配评论列表点赞状态。</p>
     *
     * @param commentIds 待查询的评论内部 id 集合（当前页）
     * @param userId     当前用户内部 id
     * @return {@code commentId -> 是否赞过} 的有序映射（LinkedHashMap，保持入参顺序）
     */
    public Map<Long, Boolean> batchIsCommentLiked(Collection<Long> commentIds, Long userId) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        commentIds.forEach(this::ensureCommentLoaded);   // 先确保全部键已加载（缺失回源）
        List<Object> hits = redis.executePipelined((RedisCallback<?>) (connection) -> {
            for (Long id : commentIds) {
                connection.sIsMember((COMMENT_USERS + id).getBytes(), String.valueOf(userId).getBytes());
            }
            return null;
        });
        Map<Long, Boolean> map = new LinkedHashMap<>();
        int i = 0;
        for (Long id : commentIds) {
            map.put(id, Boolean.TRUE.equals(hits.get(i++)));
        }
        return map;
    }
}
