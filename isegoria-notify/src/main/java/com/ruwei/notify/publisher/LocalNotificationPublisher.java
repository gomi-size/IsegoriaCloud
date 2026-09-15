package com.ruwei.notify.publisher;

import com.ruwei.model.dto.NotifyPushMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地实现：通过 WebSocket(STOMP) 直接推送给在线用户。
 *
 * <p>{@code convertAndSendToUser(内部id, "/queue/notify", msg)} —— Spring 按
 * {@code Principal.getName()}（= 内部 id 字符串）路由到该用户的<b>全部</b>会话
 * （同一账号多标签页 / 多设备都能收到）。</p>
 *
 * <p>单实例语义：simple broker 只认本进程内的会话，多实例部署需启用 STOMP relay。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class LocalNotificationPublisher implements NotificationPublisher {

    /** 用户私有目的地（客户端需订阅 {@code /user/queue/notify}） */
    private static final String DEST = "/queue/notify";

    private final SimpMessagingTemplate messagingTemplate;

    public LocalNotificationPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void push(Long internalId, NotifyPushMessage message) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(internalId), DEST, message);
        } catch (Exception e) {
            // 用户不在线或会话已断：静默失败，前端上线后从历史表拉取，不抛异常
            log.debug("WS push skipped (user offline?), internalId={}", internalId, e);
        }
    }
}