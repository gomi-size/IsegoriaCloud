package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 评论事件消息（替代旧单体本地事件 {@code CommentEvent}）。
 *
 * <p>routing key {@code evt.comment}：发布方 post，投递 notify（评论通知）与 rec（兴趣画像）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentEventMessage implements Serializable {

    /** 帖子内部 id */
    private Long postId;

    /** 评论内部 id */
    private Long commentId;

    /** 评论者内部 id（=loginId） */
    private Long loginId;

    /** 帖子作者内部 id（通知接收者） */
    private Long postUserId;

    /** 评论内容（已脱敏） */
    private String content;
}
