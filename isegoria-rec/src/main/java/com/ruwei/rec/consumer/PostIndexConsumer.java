package com.ruwei.rec.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.model.mq.PostIndexMessage;
import com.ruwei.rec.empty.Contants.EsSyncConstants;
import com.ruwei.rec.service.EsPostSyncService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ES 索引同步消费者（原单体 {@code PostIndexEventListener}）。
 *
 * <p>消费 {@code es.post.index}（发布方：post 的帖子上下架 / 编辑 / 删除，interaction 的点赞计数变更）：
 * {@code ACTION_INDEX} → 重建 / 写入文档；{@code ACTION_DELETE} → 删除文档。
 * 方法内部自带"按当前状态判断该索引还是该删"的兜底（{@code indexByPostId} 内部会调 shouldIndex），
 * 所以即使收到 INDEX 但帖子已下架，也会被正确地从索引里清掉。</p>
 *
 * <p><b>与旧实现的差异（两层保险）</b>：旧实现失败只记日志 + 写 Redis 失败集合；
 * 这里除了写失败集合，还额外 {@code basicNack(tag, false, false)} 进 DLQ ——
 * Redis 集合是"最终一致兜底"（等每日对账），DLQ 是"立刻可排查"。</p>
 *
 * <p><b>Phase 8 改造</b>：类名 {@code PostIndexEventListener} → {@code PostIndexConsumer}；
 * 本地 {@code @TransactionalEventListener} → {@code @RabbitListener}；消息体由
 * {@code PostIndexEvent} 换成 {@code PostIndexMessage}（action 由枚举改为字符串常量，
 * 避免跨服务枚举耦合）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class PostIndexConsumer {

    @Resource
    private EsPostSyncService esPostSyncService;

    /** Redis 失败重试集合（与 {@code EsReconcileTask} 共用 {@link EsSyncConstants} 的常量） */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * ES 索引同步入口。
     *
     * <p>本队列只承载 {@code PostIndexMessage} 一种类型，故参数可以直接写具体类型（类型安全）；
     * 对比 {@code RecInterestConsumer} 那条"一队列绑 4 个 rk"的队列，那里必须用 {@link Message}。</p>
     *
     * @param msg     索引消息（帖子 id + 动作）
     * @param message 原始消息（取 deliveryTag 用）
     * @param channel 用于手动 ACK
     */
    @RabbitListener(queues = ForumMqConstants.Q_ES_INDEX, concurrency = "2")
    public void onPostIndex(PostIndexMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long postId = msg.getPostId();
        try {
            if (postId == null) {
                channel.basicAck(tag, false);
                return;
            }
            if (PostIndexMessage.ACTION_DELETE.equals(msg.getAction())) {
                esPostSyncService.deleteByPostId(postId);
            } else {
                // ACTION_INDEX：内部会按当前状态判断（满足 → 索引，不满足 → 删），语义安全
                esPostSyncService.indexByPostId(postId);
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("ES 索引同步失败 postId={} action={}", postId, msg.getAction(), e);
            // 兜底 1：写失败集合，等每日对账重试（对齐旧实现：失败不丢，只是延后）
            try {
                stringRedisTemplate.opsForSet()
                        .add(EsSyncConstants.ES_SYNC_FAIL_KEY, String.valueOf(postId));
            } catch (Exception ex) {
                log.warn("写 ES 失败集合也失败 postId={}: {}", postId, ex.getMessage());
            }
            // 兜底 2：仍然 nack 进 DLQ，便于立刻排查
            channel.basicNack(tag, false, false);
        }
    }
}
