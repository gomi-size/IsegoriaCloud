package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.mq.FollowEventMessage;
import com.ruwei.notify.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户关注通知消费者（原 FollowUserEventListener）。
 *
 * <p>消费 {@code evt.follow}：通知<b>被关注者</b>（{@code type=4} 关注，{@code targetType=2} 用户）。</p>
 *
 * <p><b>⚠️ 只处理"关注"，丢弃"取关"</b>：旧实现没判 {@code action}，导致跨天取关时会给对方
 * 发一条"XX 关注了你"的假通知（幂等键按天，当天关注+取关只留一条）。这里显式判 action，
 * 顺手修掉该缺陷。</p>
 *
 * <p>幂等键 {@code follow:{actorId}:{followeeId}:{yyyyMMdd}}（同一对用户每天最多一条）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class FollowConsumer {

    @DubboReference
    private InnerUserService innerUserService;

    private final NotificationService notificationService;

    public FollowConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_FOLLOW, concurrency = "2")
    public void onFollow(FollowEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handle(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("关注通知处理失败 loginId={} targetId={}", msg.getLoginId(), msg.getTargetId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(FollowEventMessage msg) {
        // ★ 只处理关注；取关（ACTION_CANCEL）直接丢弃，不落通知
        if (!FollowEventMessage.ACTION_FOLLOW.equals(msg.getAction())) {
            log.debug("跳过非关注事件 action={} loginId={}", msg.getAction(), msg.getLoginId());
            return;
        }
        Long actorId = msg.getLoginId();     // ⚠️ 旧名 getActorId()
        Long followeeId = msg.getTargetId();
        if (actorId == null || followeeId == null) {
            return;
        }
        User user = innerUserService.getById(actorId);
        if (user == null) {
            return;   // 关注者已注销
        }

        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        SendNotificationDTO dto = new SendNotificationDTO();
        dto.setReceiverId(followeeId);        // 收：被关注者
        dto.setSenderId(actorId);             // 发：关注者
        dto.setType(4);                       // 4=关注
        dto.setTargetType(2);                 // 2=用户（前端跳关注者主页）
        dto.setTargetId(followeeId);
        dto.setContent(user.getNickname() + "在" + time + "时间，关注了你");
        dto.setBizKey("follow:" + actorId + ":" + followeeId + ":" + todayStr);
        notificationService.sendNotification(dto);
    }
}