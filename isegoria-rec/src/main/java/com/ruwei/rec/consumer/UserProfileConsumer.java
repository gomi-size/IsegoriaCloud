package com.ruwei.rec.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.model.mq.UserProfileMessage;
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
 * 用户资料变更 → ES 重建消费者（原单体 {@code UserProfileEventListener}）。
 *
 * <p>消费 {@code es.user.profile}（发布方：user 服务改昵称 / 头像）。
 * <b>为什么要重建</b>：{@code PostDoc} 里冗余了作者昵称 / 头像（这样搜索结果不用回表就能渲染卡片），
 * 所以资料一变，该作者的<b>全部帖子</b>的索引文档都要重建，否则搜索与推荐里会长期显示旧昵称。</p>
 *
 * <p>重建范围由 {@code EsPostSyncService.reindexByAuthorId} 内部决定（该作者全部帖子：
 * 满足索引条件的重建、不满足的顺手清掉脏文档），天然幂等。</p>
 *
 * <p><b>失败处置</b>：把该作者全部帖子 id 写入 Redis 失败集合
 * （{@link EsSyncConstants#ES_SYNC_FAIL_KEY}），交给每日对账逐帖重试，再 nack 进 DLQ。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class UserProfileConsumer {

    @Resource
    private EsPostSyncService esPostSyncService;

    /** Redis 失败重试集合（与 PostIndexConsumer / EsReconcileTask 共用同一常量） */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用户资料变更入口。
     *
     * @param msg     资料变更消息（userId）
     * @param message 原始消息（取 deliveryTag 用）
     * @param channel 用于手动 ACK
     */
    @RabbitListener(queues = ForumMqConstants.Q_ES_PROFILE, concurrency = "2")
    public void onUserProfileUpdated(UserProfileMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long userId = msg.getUserId();
        try {
            if (userId == null) {
                channel.basicAck(tag, false);
                return;
            }
            esPostSyncService.reindexByAuthorId(userId);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("用户资料变更触发 ES 重建失败 userId={}", userId, e);
            // 把该作者的全部帖子 id 写入失败集合，等每日对账逐帖重试（对齐旧实现）
            try {
                for (Long postId : esPostSyncService.listPostIdsByAuthor(userId)) {
                    stringRedisTemplate.opsForSet()
                            .add(EsSyncConstants.ES_SYNC_FAIL_KEY, String.valueOf(postId));
                }
            } catch (Exception ex) {
                log.warn("写 ES 失败集合也失败 userId={}: {}", userId, ex.getMessage());
            }
            channel.basicNack(tag, false, false);
        }
    }
}
