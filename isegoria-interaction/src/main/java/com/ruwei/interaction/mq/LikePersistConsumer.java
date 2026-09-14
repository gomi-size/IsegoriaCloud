package com.ruwei.interaction.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.interaction.mapper.CommentLikeMapper;
import com.ruwei.interaction.mapper.PostLikeMapper;
import com.ruwei.model.dto.LikePersistMessage;
import com.ruwei.model.entity.CommentLike;
import com.ruwei.model.entity.PostLike;
import com.ruwei.model.mq.PostIndexMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.time.Duration;

/**
 * 点赞异步落库消费者（「Redis 先行 + MQ 落库」写路径的落库端）。
 *
 * <p>消费 {@code like.exchange} 的 {@link LikePersistMessage}，落库到 {@code post_like} /
 * {@code comment_like}，并回写主表冗余计数列。跨服务改造后：
 * <b>计数不直改 post / comment 表，一律经 {@code InnerPostService.incrementPostCount} /
 * {@code incrementCommentCount}</b>。</p>
 *
 * <h3>可靠性四层兜底</h3>
 * <ol>
 *   <li>生产端 publisher-confirm（{@code LikeServiceImpl.initConfirmCallback}）+ 发送异常同步直写；</li>
 *   <li>消费端幂等：{@code SETNX like:mq:dedup:{eventId}}（10 分钟 TTL）；</li>
 *   <li>DB 唯一键 {@code ukPostUser} / {@code ukCommentUser} 兜底（重复 INSERT 视为幂等成功）；</li>
 *   <li>手动 ACK：异常 {@code basicNack(requeue=false)} → 死信队列人工介入。</li>
 * </ol>
 *
 * <p>计数防漂移：仅当 INSERT 影响行数 = 1 才 +1、仅当 DELETE 影响行数 = 1 才 -1。</p>
 *
 * @author ruwei
 */
@Component
@Slf4j
public class LikePersistConsumer {

    /** MQ 消费去重标记 Redis Key 前缀 */
    private static final String DEDUP_PREFIX = "like:mq:dedup:";
    /** 去重标记过期时间：10 分钟 */
    private static final Duration DEDUP_TTL = Duration.ofMinutes(10);

    @Resource
    private PostLikeMapper postLikeMapper;
    @Resource
    private CommentLikeMapper commentLikeMapper;
    @Resource
    private StringRedisTemplate redis;
    /** 发 es.post.index（forum.exchange）请求 rec 重建 ES 文档 */
    @Resource
    private RabbitTemplate rabbitTemplate;
    /** post 服务（Dubbo）：冗余计数原子增减 */
    @DubboReference
    private InnerPostService innerPostService;

    @RabbitListener(queues = LikeMqConfig.POST_QUEUE, concurrency = "4")
    public void onPostMessage(LikePersistMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            if (!dedup(msg.getEventId())) {
                channel.basicAck(tag, false);
                return;
            }
            persistPost(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("帖子点赞落库失败 eventId={} targetId={}", msg.getEventId(), msg.getTargetId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    @RabbitListener(queues = LikeMqConfig.COMMENT_QUEUE, concurrency = "4")
    public void onCommentMessage(LikePersistMessage msg, Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            if (!dedup(msg.getEventId())) {
                channel.basicAck(tag, false);
                return;
            }
            persistComment(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("评论点赞落库失败 eventId={} targetId={}", msg.getEventId(), msg.getTargetId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    /** SETNX 幂等去重：true=首次处理，false=已处理过（直接 ACK 跳过） */
    private boolean dedup(String eventId) {
        Boolean ok = redis.opsForValue().setIfAbsent(DEDUP_PREFIX + eventId, "1", DEDUP_TTL);
        return Boolean.TRUE.equals(ok);
    }

    private void persistPost(LikePersistMessage msg) {
        Long postId = msg.getTargetId();
        Long userId = msg.getUserId();
        if (msg.getAction() == 1) {
            PostLike record = new PostLike();
            record.setPostId(postId);
            record.setUserId(userId);
            record.setCreatedAt(new java.util.Date());
            try {
                if (postLikeMapper.insert(record) > 0) {      // 仅真正新增才 +1，防漂移
                    innerPostService.incrementPostCount(postId, "likeCount", 1);
                    publishPostIndex(postId);
                }
            } catch (DuplicateKeyException ex) {
                log.warn("点赞幂等跳过（唯一键命中）postId={} userId={}", postId, userId);
            }
        } else {
            int removed = postLikeMapper.delete(new LambdaQueryWrapper<PostLike>()
                    .eq(PostLike::getPostId, postId).eq(PostLike::getUserId, userId));
            if (removed > 0) {
                innerPostService.incrementPostCount(postId, "likeCount", -1);
                publishPostIndex(postId);
            }
        }
    }

    private void persistComment(LikePersistMessage msg) {
        Long commentId = msg.getTargetId();
        Long userId = msg.getUserId();
        if (msg.getAction() == 1) {
            CommentLike record = new CommentLike();
            record.setCommentId(commentId);
            record.setUserId(userId);
            record.setCreatedAt(new java.util.Date());
            try {
                if (commentLikeMapper.insert(record) > 0) {
                    innerPostService.incrementCommentCount(commentId, "likeCount", 1);
                }
            } catch (Exception ex) {
                log.warn("评论点赞幂等跳过（唯一键命中）commentId={} userId={}", commentId, userId);
            }
        } else {
            int removed = commentLikeMapper.delete(new LambdaQueryWrapper<CommentLike>()
                    .eq(CommentLike::getCommentId, commentId).eq(CommentLike::getUserId, userId));
            if (removed > 0) {
                innerPostService.incrementCommentCount(commentId, "likeCount", -1);
            }
        }
    }

    /** 通知 rec 重建 ES 文档（点赞计数真实变更时） */
    private void publishPostIndex(Long postId) {
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_POST_INDEX,
                new PostIndexMessage(postId, PostIndexMessage.ACTION_INDEX));
    }
}