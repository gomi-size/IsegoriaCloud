package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.mq.ShareEventMessage;
import com.ruwei.notify.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 站内分享通知消费者（原 ShareEventListener）。
 *
 * <p>消费 {@code evt.share}：通知<b>被分享者</b>（{@code type=8} 分享/转发）。</p>
 *
 * <p>幂等键按天：{@code share:{actorId}:{targetUserId}:{postId}:{yyyyMMdd}}
 * ——同一人同一天把同一帖分享给同一人只通知一次（防重复骚扰）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class ShareConsumer {

    @DubboReference
    private InnerUserService innerUserService;

    private final NotificationService notificationService;

    public ShareConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_SHARE, concurrency = "2")
    public void onShare(ShareEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handle(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("分享通知处理失败 postId={} targetUserId={}", msg.getPostId(), msg.getTargetUserId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(ShareEventMessage msg) {
        Long actorId = msg.getLoginId();          // ⚠️ 旧名 getActorId()
        Long postId = msg.getPostId();
        Long targetUserId = msg.getTargetUserId();
        if (actorId == null || postId == null || targetUserId == null) {
            return;
        }
        // 分享给自己 → 不通知
        if (actorId.equals(targetUserId)) {
            return;
        }
        User actor = innerUserService.getById(actorId);
        if (actor == null) {
            return;   // 分享者已注销
        }

        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        SendNotificationDTO dto = new SendNotificationDTO();
        dto.setReceiverId(targetUserId);      // 收：被分享者
        dto.setSenderId(actorId);             // 发：分享者
        dto.setType(8);                       // 8=分享/转发
        dto.setTargetType(1);                 // 帖子
        dto.setTargetId(postId);
        dto.setContent(actor.getNickname() + "分享了一个帖子给你");
        dto.setBizKey("share:" + actorId + ":" + targetUserId + ":" + postId + ":" + todayStr);
        notificationService.sendNotification(dto);
    }
}