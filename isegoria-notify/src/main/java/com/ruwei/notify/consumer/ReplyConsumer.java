package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.mq.ReplyEventMessage;
import com.ruwei.notify.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 楼中楼回复通知消费者（原 CommentEventListener#onReply）。
 *
 * <p>消费 {@code evt.reply}：通知<b>被回复者</b>（{@code type=3} 回复）。</p>
 *
 * <p><b>只发一条</b>：回复者回帖主的楼中楼时，只产生 {@code type=3}，不会再产生 {@code type=2}
 * （发布方对不同场景发不同 routing key，不会双发）。</p>
 *
 * <p>幂等键 {@code reply:{commentId}:{replyToUserId}}。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class ReplyConsumer {

    @DubboReference
    private InnerUserService innerUserService;

    private final NotificationService notificationService;

    public ReplyConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_REPLY, concurrency = "2")
    public void onReply(ReplyEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handle(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("回复通知处理失败 commentId={} replyToUserId={}",
                    msg.getCommentId(), msg.getReplyToUserId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(ReplyEventMessage msg) {
        Long postId = msg.getPostId();
        Long commentId = msg.getCommentId();
        Long commentUserId = msg.getLoginId();          // ⚠️ 旧名 getCommentUserId()
        Long replyToUserId = msg.getReplyToUserId();
        if (postId == null || commentId == null || commentUserId == null || replyToUserId == null) {
            return;
        }
        // 自己回自己 → 不通知
        if (commentUserId.equals(replyToUserId)) {
            return;
        }
        User user = innerUserService.getById(commentUserId);
        if (user == null) {
            return;
        }

        SendNotificationDTO dto = new SendNotificationDTO();
        dto.setReceiverId(replyToUserId);     // 收：被回复者
        dto.setSenderId(commentUserId);       // 发：回复者
        dto.setType(3);                       // 3=回复
        dto.setTargetType(1);                 // 帖子
        dto.setTargetId(postId);
        dto.setCommentId(commentId);
        dto.setContent(user.getNickname() + "回复了你：" + TextPreview.of(msg.getContent()));
        dto.setBizKey("reply:" + commentId + ":" + replyToUserId);
        notificationService.sendNotification(dto);
    }


}