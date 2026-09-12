package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 楼中楼回复事件消息（替代旧单体本地事件 {@code ReplyEvent}）。
 *
 * <p>routing key {@code evt.reply}：发布方 post，仅投递 notify（回复通知）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplyEventMessage implements Serializable {

    /** 帖子内部 id */
    private Long postId;

    /** 评论（回复）内部 id */
    private Long commentId;

    /** 回复者内部 id（=loginId） */
    private Long loginId;

    /** 被回复者内部 id（通知接收者） */
    private Long replyToUserId;

    /** 帖子作者内部 id */
    private Long postUserId;

    /** 回复内容（已脱敏） */
    private String content;
}
