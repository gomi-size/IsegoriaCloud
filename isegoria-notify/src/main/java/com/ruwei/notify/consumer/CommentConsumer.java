package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.mq.CommentEventMessage;
import com.ruwei.notify.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 一级评论通知消费者（原 CommentEventListener#onComment）。
 *
 * <p>消费 {@code evt.comment}：通知<b>帖子作者</b>（{@code type=2} 评论），
 * {@code commentId} 落库后供前端锚定到具体评论。</p>
 *
 * <p>幂等键 {@code comment:{postId}:{commentId}:{postUserId}}。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class CommentConsumer {

    @DubboReference
    private InnerUserService innerUserService;

    private final NotificationService notificationService;

    public CommentConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_COMMENT, concurrency = "2")
    public void onComment(CommentEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handle(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("评论通知处理失败 postId={} commentId={}", msg.getPostId(), msg.getCommentId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(CommentEventMessage msg) {
        Long postId = msg.getPostId();
        Long commentId = msg.getCommentId();
        Long commentUserId = msg.getLoginId();    // ⚠️ 旧事件叫 getCommentUserId()，新消息叫 getLoginId()
        Long postUserId = msg.getPostUserId();
        if (postId == null || commentId == null || commentUserId == null || postUserId == null) {
            return;
        }
        // 自己评论自己的帖子 → 不通知自己
        if (commentUserId.equals(postUserId)) {
            return;
        }
        User user = innerUserService.getById(commentUserId);
        if (user == null) {
            return;   // 评论者已注销
        }

        SendNotificationDTO dto = new SendNotificationDTO();
        dto.setReceiverId(postUserId);        // 收：帖主
        dto.setSenderId(commentUserId);       // 发：评论者
        dto.setType(2);                       // 2=评论
        dto.setTargetType(1);                 // 帖子
        dto.setTargetId(postId);
        dto.setCommentId(commentId);          // 评论锚点
        dto.setContent(user.getNickname() + "评论了你：" + TextPreview.of(msg.getContent()));
        dto.setBizKey("comment:" + postId + ":" + commentId + ":" + postUserId);
        notificationService.sendNotification(dto);
    }


}