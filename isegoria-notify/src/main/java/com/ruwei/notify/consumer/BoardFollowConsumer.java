package com.ruwei.notify.consumer;

import com.rabbitmq.client.Channel;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.User;
import com.ruwei.model.mq.BoardFollowEventMessage;
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
 * 板块关注通知消费者（原 BoardFollowEventListener）。
 *
 * <p>消费 {@code evt.boardfollow}：通知<b>板块创建者（吧主）</b>（{@code type=4}，
 * {@code targetType=3} 板块）。与用户关注复用 {@code type=4}，靠 {@code targetType} 区分。</p>
 *
 * <p>吧主关注自己的板块不发通知；幂等键 {@code boardFollow:{actorId}:{boardId}:{yyyyMMdd}}。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class BoardFollowConsumer {

    @DubboReference
    private InnerUserService innerUserService;

    /** post 服务（Dubbo）：取板块名拼文案（board 表归 post） */
    @DubboReference
    private InnerPostService innerPostService;

    private final NotificationService notificationService;

    public BoardFollowConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = ForumMqConstants.Q_NOTIFY_BOARD_FOLLOW, concurrency = "2")
    public void onBoardFollow(BoardFollowEventMessage msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handle(msg);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("板块关注通知处理失败 boardId={} creatorId={}",
                    msg.getBoardId(), msg.getBoardCreatorId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handle(BoardFollowEventMessage msg) {
        Long actorId = msg.getLoginId();               // ⚠️ 旧名 getActorId()
        Long boardId = msg.getBoardId();
        Long ownerId = msg.getBoardCreatorId();        // ⚠️ 旧名 getOwnerId()
        if (actorId == null || boardId == null || ownerId == null) {
            return;
        }
        // 吧主关注自己的板块 → 不通知自己
        if (actorId.equals(ownerId)) {
            return;
        }
        User user = innerUserService.getById(actorId);
        Board board = innerPostService.getBoardById(boardId);
        if (user == null || board == null) {
            // 关注者或板块已被删除 → 静默跳过
            // （旧代码这里 ThrowUtils.throwIf(true, ...) 抛异常，在异步监听器里异常只会被吞掉并打日志，
            //   语义上等价于“跳过”，但会污染错误日志。这里改为 return。）
            log.info("跳过板块关注通知：关注者或板块已不存在 actorId={} boardId={}", actorId, boardId);
            return;
        }

        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        SendNotificationDTO dto = new SendNotificationDTO();
        dto.setReceiverId(ownerId);           // 收：吧主
        dto.setSenderId(actorId);             // 发：关注者
        dto.setType(4);                       // 4=关注
        dto.setTargetType(3);                 // 3=板块（前端跳板块主页）
        dto.setTargetId(boardId);
        dto.setContent(user.getNickname() + "在" + time + "关注了你的板块「" + board.getName() + "」");
        dto.setBizKey("boardFollow:" + actorId + ":" + boardId + ":" + todayStr);
        notificationService.sendNotification(dto);
    }
}