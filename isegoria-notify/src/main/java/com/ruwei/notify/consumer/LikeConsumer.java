package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.mq.LikeEventMessage;
import com.ruwei.notify.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 点赞通知消费者（原 LikeEventListener）。
 *
 * <p>消费 {@code evt.like}：生成 {@code type=1} 点赞通知 + WS 推送。
 * 幂等键 {@code like:post:{actorId}:{postId}}（同一个人对同一帖子的点赞只通知一次）。</p>
 *
 * <p>点赞是高频动作、消息量大，所以本队列单独一条（不与评论/回复共用），避免互相拖慢。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class LikeConsumer {

    @DubboReference
    private InnerUserService innerUserService;

    private final NotificationService notificationService;

    public LikeConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_LIKE, concurrency = "2")
    public void onLike(LikeEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            //处理信息
            handle(msg);
            // 处理成功 → 确认，消息从队列移除
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("点赞通知处理失败 actorId={} postId={}", msg.getActorId(), msg.getPostId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(LikeEventMessage msg) {
        Long actorId = msg.getActorId();
        Long postId = msg.getPostId();
        Long postUserId = msg.getPostUserId();
        if (actorId == null || postId == null || postUserId == null) {
            return;
        }
        // 自己赞自己不发通知（发布方已判过一次，这里再兜一次：MQ 消息可能被人工重放）
        if (actorId.equals(postUserId)) {
            return;
        }
        User actor = innerUserService.getById(actorId);
        if (actor == null) {
            return;   // 点赞者已注销
        }

        SendNotificationDTO dto = new SendNotificationDTO();
        dto.setReceiverId(postUserId);     // 收：帖子作者
        dto.setSenderId(actorId);          // 发：点赞者
        dto.setType(1);                    // 1=点赞
        dto.setTargetType(1);              // 1=帖子
        dto.setTargetId(postId);
        dto.setContent(actor.getNickname() + "赞了你的帖子");
        dto.setBizKey("like:post:" + actorId + ":" + postId);
        notificationService.sendNotification(dto);
    }
}