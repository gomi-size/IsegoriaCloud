package com.ruwei.rec.consumer;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.model.entity.Post;
import com.ruwei.model.entity.userBehavior;
import com.ruwei.model.mq.CommentEventMessage;
import com.ruwei.model.mq.LikeEventMessage;
import com.ruwei.model.mq.ShareEventMessage;
import com.ruwei.model.mq.ViewEventMessage;
import com.ruwei.rec.manager.RecCacheManager;
import com.ruwei.rec.service.UserbehaviorService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 推荐兴趣信号消费者（原单体 {@code RecInterestListener}）。
 *
 * <p>消费 {@code forum.rec.interest.queue}，该队列<b>同时绑定 4 条 routing key</b>：
 * {@code evt.like / evt.comment / evt.share / evt.view}。本类做两件事：
 * ① 写行为流水 {@code user_behavior}（离线画像原料）；② INCR 短期兴趣（Redis）。
 * 换句话说：<b>它是推荐系统"用户兴趣"的唯一写入路径</b>（读侧在 {@code RecServiceImpl.loadProfile}）。</p>
 *
 * <h3>为什么方法参数用 {@link Message} 而不是具体类型（本类最易踩的坑）</h3>
 * <p>一条队列里混了 <b>4 种 Java 类型</b>。如果参数写死 {@code LikeEventMessage}，收到
 * {@code CommentEventMessage} 时消息头 {@code __TypeId__} 里的类型与参数类型不一致，
 * 强转会抛 {@code ClassCastException} —— 注意这不是"收不到"，而是"收到了但炸了"，
 * 排查时更绕。所以这里统一用 {@code Message} + {@link MessageConverter#fromMessage}，
 * 由消息头 {@code __TypeId__}（发布方 {@code Jackson2JsonMessageConverter} 写好的完整类名）
 * 决定实际类型，再用 {@code instanceof} 分发。</p>
 *
 * <h3>信号强度与去噪</h3>
 * <p>点赞 / 评论 / 分享 = 强信号 1.0；浏览 = 弱信号 0.2（{@code rec.interest.view-delta}）。
 * 去噪由发布方负责（LikeEvent 已过滤自赞；ViewEvent 仅登录用户发布），本类不重复判断。
 * 落库 / 写兴趣失败只告警并 nack，不影响主流程（行为数据丢一条不影响功能）。</p>
 *
 * <h3>Phase 8 改造要点</h3>
 * <ul>
 *   <li>旧 {@code @Async + @TransactionalEventListener(AFTER_COMMIT)} → {@code @RabbitListener}
 *       （并发由 listener container 的 {@code concurrency} 管，不再依赖 {@code @Async}）；</li>
 *   <li>旧 {@code @Resource PostService} → {@code @DubboReference InnerPostService}；</li>
 *   <li><b>字段改名（照抄旧代码必错的地方）</b>：
 *       {@code e.getActorId()} → {@code msg.getActorId()}（Like）/
 *       {@code msg.getLoginId()}（<b>Comment、Share 改名了</b>）/
 *       {@code msg.getUserId()}（View 未改名）；
 *       {@code e.getTopic()} → {@code msg.getPostTopic()}
 *       （{@code ViewEventMessage} 的字段名是 {@code PostTopic}，首字母大写）。</li>
 * </ul>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class RecInterestConsumer {

    /** 强信号增量（点赞/评论/分享） */
    private static final double STRONG_DELTA = 1.0;
    /** 行为来源：推荐流 */
    private static final int SOURCE_REC = 1;
    /** 行为动作：浏览（详情页打开） */
    private static final int ACTION_VIEW = 2;
    /** 行为动作：点赞 */
    private static final int ACTION_LIKE = 4;
    /** 行为动作：评论 */
    private static final int ACTION_COMMENT = 5;
    /** 行为动作：分享 */
    private static final int ACTION_SHARE = 7;
    /** 兴趣维度：标签 */
    private static final int DIM_TAG = 2;
    /** 兴趣维度：内容形态 */
    private static final int DIM_TYPE = 3;
    /** 兴趣维度：板块 */
    private static final int DIM_BOARD = 4;
    /** 兴趣维度：作者（只写不读，防大 V 垄断精排） */
    private static final int DIM_AUTHOR = 5;

    /** 浏览弱信号增量 */
    @Value("${rec.interest.view-delta:0.2}")
    private double viewDelta;

    /** post 服务：事件只带 actorId / postId，帖子详情（标签/类型/板块/作者）需回 post 服务补齐 */
    @DubboReference
    private InnerPostService innerPostService;

    @Resource
    private UserbehaviorService userbehaviorService;

    @Resource
    private RecCacheManager recCacheManager;

    /**
     * 用于按 {@code __TypeId__} 头还原具体消息类型。
     *
     * <p>显式按 Bean 名注入（{@code RecMqConfig#jacksonMessageConverter}）——若容器里将来
     * 出现第二个 {@code MessageConverter}，按类型注入会"找到多个候选"而启动失败。</p>
     */
    @Resource(name = "jacksonMessageConverter")
    private MessageConverter messageConverter;

    /**
     * 兴趣事件统一入口（4 类事件合一）。
     *
     * <p>手动 ACK 口径与 Phase 5 的 {@code LikePersistConsumer} 一致：
     * 成功 {@code basicAck}，异常 {@code basicNack(tag, false, false)} → 进 DLQ。
     * 代价是 {@code retry.max-attempts} 不生效（想让重试生效就得抛异常给容器）。</p>
     *
     * @param message 原始消息（不声明具体类型，见类注释）
     * @param channel 用于手动 ACK
     */
    @RabbitListener(queues = ForumMqConstants.Q_REC_INTEREST, concurrency = "2")
    public void onInterest(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String rk = message.getMessageProperties().getReceivedRoutingKey();
        try {
            Object payload = messageConverter.fromMessage(message);
            if (payload instanceof LikeEventMessage m) {
                handleLike(m);
            } else if (payload instanceof CommentEventMessage m) {
                handleComment(m);
            } else if (payload instanceof ShareEventMessage m) {
                handleShare(m);
            } else if (payload instanceof ViewEventMessage m) {
                handleView(m);
            } else {
                log.warn("忽略未知兴趣事件 rk={} type={}", rk,
                        payload == null ? null : payload.getClass().getName());
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("兴趣事件处理失败 rk={}", rk, e);
            channel.basicNack(tag, false, false);
        }
    }

    /** 点赞：action=4，强信号。事件只带 actorId/postId，帖子详情需回 post 服务补齐 */
    private void handleLike(LikeEventMessage msg) {
        Post post = innerPostService.getById(msg.getPostId());
        if (post == null) {
            return;
        }
        saveBehavior(msg.getActorId(), post.getId(), ACTION_LIKE);
        incrInterests(msg.getActorId(), post, STRONG_DELTA);
    }

    /** 评论（一级评论）：action=5，强信号。二级回复 ReplyEvent 口径相同，本期未接入，需要时按同样方式追加分支 */
    private void handleComment(CommentEventMessage msg) {
        Post post = innerPostService.getById(msg.getPostId());
        if (post == null) {
            return;
        }
        // ⚠️ 旧事件叫 getCommentUserId()，新消息叫 getLoginId()
        saveBehavior(msg.getLoginId(), post.getId(), ACTION_COMMENT);
        incrInterests(msg.getLoginId(), post, STRONG_DELTA);
    }

    /** 站内分享：action=7，强信号 */
    private void handleShare(ShareEventMessage msg) {
        Post post = innerPostService.getById(msg.getPostId());
        if (post == null) {
            return;
        }
        // ⚠️ 旧事件叫 getActorId()，新消息叫 getLoginId()
        saveBehavior(msg.getLoginId(), post.getId(), ACTION_SHARE);
        incrInterests(msg.getLoginId(), post, STRONG_DELTA);
    }

    /**
     * 浏览：action=2，弱信号。
     *
     * <p>{@code ViewEventMessage} 已携带 topic / type / boardId，<b>免查 post</b>
     * （省一次 Dubbo 往返，浏览是最高频事件）。</p>
     */
    private void handleView(ViewEventMessage msg) {
        if (msg.getUserId() == null) {
            return;   // 防御：正常链路 recordView 已过滤游客
        }
        saveBehavior(msg.getUserId(), msg.getPostId(), ACTION_VIEW);
        // ⚠️ 旧事件 getTopic() → 新消息 getPostTopic()（字段名 PostTopic，首字母大写）
        incrInterests(msg.getUserId(), msg.getPostTopic(), msg.getType(), msg.getBoardId(), viewDelta);
    }

    // ==================== 私有工具（从旧 RecInterestListener 原样搬运） ====================

    /**
     * 写行为流水 {@code user_behavior}（失败只告警，不影响主流程）。
     *
     * @param userId 行为主体内部 id
     * @param postId 帖子内部 id
     * @param action 行为码（2 浏览 / 4 点赞 / 5 评论 / 7 分享）
     */
    private void saveBehavior(Long userId, Long postId, int action) {
        if (userId == null || postId == null) {
            return;
        }
        userBehavior ub = new userBehavior();
        ub.setUserId(userId);
        ub.setPostId(postId);
        ub.setAction(action);
        ub.setSource(SOURCE_REC);
        ub.setPosition(0);
        ub.setDwellSec(0);
        ub.setExtras("");
        try {
            userbehaviorService.save(ub);
        } catch (Exception e) {
            log.warn("行为落库失败 userId={} postId={} action={}: {}", userId, postId, action, e.getMessage());
        }
    }

    /** 帖子 → 兴趣维度批量 INCR（dim2 标签 / dim3 类型 / dim4 板块 / dim5 作者） */
    private void incrInterests(Long uid, Post post, double delta) {
        if (uid == null) {
            return;
        }
        incrInterests(uid, post.getTopic(), post.getType(), post.getBoardId(), delta);
        // 作者维度（dim=5）：写兴趣但不参与精排（防大 V 垄断），Phase 2 再启用
        if (post.getUserId() != null) {
            recCacheManager.incrInterest(uid, DIM_AUTHOR, String.valueOf(post.getUserId()), delta);
        }
    }

    /**
     * 维度值直写版（浏览事件免查 post）。
     *
     * @param uid     用户内部 id
     * @param topic   帖子标签串（逗号分隔的 tagId）
     * @param type    内容形态码
     * @param boardId 板块内部 id
     * @param delta   增量
     */
    private void incrInterests(Long uid, String topic, Integer type, Long boardId, double delta) {
        if (uid == null) {
            return;
        }
        if (StrUtil.isNotBlank(topic)) {
            for (String t : StrUtil.split(topic, ',')) {
                String trimmed = StrUtil.trim(t);
                if (NumberUtil.isLong(trimmed)) {
                    recCacheManager.incrInterest(uid, DIM_TAG, trimmed, delta);
                }
            }
        }
        if (type != null) {
            recCacheManager.incrInterest(uid, DIM_TYPE, String.valueOf(type), delta);
        }
        if (boardId != null) {
            recCacheManager.incrInterest(uid, DIM_BOARD, String.valueOf(boardId), delta);
        }
    }
}
