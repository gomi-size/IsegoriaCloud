package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.mq.AdminEventMessage;
import com.ruwei.notify.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管理员审核结果通知消费者（原 AdminEventListener）。
 *
 * <p>消费 {@code evt.admin}：把审核意见通知给<b>帖子作者</b>（{@code type=6} 系统通知）。</p>
 *
 * <h3>与旧实现的差异（务必理解）</h3>
 * <ol>
 *   <li>旧事件字段名是错的：{@code AdminEvent.adminId} 实际装的是<b>帖子 id</b>
 *       （发布方 {@code PostServiceImpl:783} 传的是 {@code id}）。新消息 {@link AdminEventMessage}
 *       把名字改对了（{@code postId} / {@code postUserId} / {@code message}），
 *       但<b>不含管理员 id</b>（旧代码也没传过真的管理员 id）。因此 {@code senderId} 只能置 null，
 *       前端会按「系统消息」渲染。</li>
 *   <li>旧代码 {@code targetId} 填的是接收者自己的 id（点通知无处可跳）。
 *       这里改为 <b>{@code targetId = postId}</b>，前端可跳到被审核的帖子。
 *       <b>若前端已按"跳用户主页"实现，请把下面标注的那行换成 {@code msg.getPostUserId()}</b>。</li>
 *   <li>{@code bizKey = null}：审核通知<b>不参与幂等去重</b>，与旧行为一致
 *       （管理员每次留言都是一条独立通知）。<b>代价</b>：MQ 重复投递会产生重复通知
 *       （极少见：只在消费失败进 DLQ 后人工重放时发生）。若要根治，可改成
 *       {@code "admin:" + postId + ":" + System.currentTimeMillis()} 之类的自增键
 *       —— 但那只是把"重复"变成"无法人工重放"，本 Phase 保持 null。</li>
 * </ol>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class AdminConsumer {

    private final NotificationService notificationService;

    public AdminConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_ADMIN, concurrency = "2")
    public void onAdminAudit(AdminEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handle(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("审核结果通知处理失败 postId={} postUserId={}", msg.getPostId(), msg.getPostUserId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(AdminEventMessage msg) {
        Long postId = msg.getPostId();
        Long postUserId = msg.getPostUserId();
        String message = msg.getMessage();
        if (postId == null || postUserId == null) {
            return;
        }

        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String content = "系统在：" + time + "：" + (message == null ? "" : message);

        SendNotificationDTO dto = new SendNotificationDTO();
        dto.setReceiverId(postUserId);        // 收：帖子作者
        dto.setSenderId(null);                // 发：null（无管理员 id，前端按系统消息渲染）
        dto.setType(6);                       // 6=系统通知
        dto.setTargetType(1);                 // 帖子
        dto.setTargetId(postId);              // ★ 推荐：帖子 id（若前端要跳用户主页，改 postUserId）
        dto.setContent(content);
        dto.setBizKey(null);                  // 不幂等（见类注释第 3 条）
        notificationService.sendNotification(dto);
    }
}