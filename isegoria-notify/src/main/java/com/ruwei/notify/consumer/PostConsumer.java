package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.Post;
import com.ruwei.model.entity.User;
import com.ruwei.model.mq.PostEventMessage;
import com.ruwei.notify.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 新帖发布通知消费者（原 PostEventListener）：帖子审核通过后，向作者<b>全部粉丝</b>逐条发通知。
 *
 * <p><b>设计要点</b>：粉丝列表由发布方（post 服务审核事务内）查好放进消息，
 * 本消费者<b>零额外查询</b>即可群发 —— 避免消费端为拿粉丝再发一次跨服务调用。</p>
 *
 * <p>幂等键 {@code postPublish:{actorId}:{postId}:{fanId}:{yyyyMMdd}}
 * ——每个粉丝对同一帖每天最多一条。</p>
 *
 * <p><b>性能提示</b>：大 V 可能有几万粉丝 → 单条消息触发几万次 insert。
 * 本 Phase 与旧实现保持一致（同步逐条），量大时再改批量插入 + 异步分批。
 * 若耗时过长导致 ACK 超时，可调大 {@code spring.rabbitmq.listener.simple.prefetch} 或拆分。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class PostConsumer {

    /** 单个粉丝的正文预览截断长度（沿用旧实现的文案风格；此处文案不含正文，保留常量备用） */
    private static final int PREVIEW_MAX_LENGTH = 50;

    @DubboReference
    private InnerUserService innerUserService;

    /** post 服务（Dubbo）：取帖子标题拼文案 */
    @DubboReference
    private InnerPostService innerPostService;

    private final NotificationService notificationService;

    public PostConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_POST, concurrency = "2")
    public void onPostPublished(PostEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handle(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("新帖发布通知处理失败 postId={} postUserId={}",
                    msg.getPostId(), msg.getPostUserId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(PostEventMessage msg) {
        Long actorId = msg.getPostUserId();    // ⚠️ 旧名 getActorId()（作者）
        Long postId = msg.getPostId();
        List<Long> fans = msg.getFans();       // ⚠️ 旧名 getFollowList()
        if (actorId == null || postId == null || fans == null || fans.isEmpty()) {
            return;
        }
        User user = innerUserService.getById(actorId);
        Post post = innerPostService.getById(postId);
        if (user == null || post == null) {
            log.info("跳过新帖发布通知：作者或帖子已不存在 actorId={} postId={}", actorId, postId);
            return;
        }

        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String content = user.getNickname() + "发布了新帖子《" + post.getTitle() + "》";

        for (Long fanId : fans) {
            if (fanId == null) {
                continue;
            }
            SendNotificationDTO dto = new SendNotificationDTO();
            dto.setReceiverId(fanId);        // 收：粉丝
            dto.setSenderId(actorId);        // 发：作者
            dto.setType(6);                  // 6=系统通知（帖子发布推送）
            dto.setTargetType(1);            // 帖子
            dto.setTargetId(postId);
            dto.setContent(content);
            dto.setBizKey("postPublish:" + actorId + ":" + postId + ":" + fanId + ":" + todayStr);
            notificationService.sendNotification(dto);
        }
    }
}