package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 审核结果事件消息（替代旧单体本地事件 {@code AdminEvent}）。
 *
 * <p>routing key {@code evt.admin}：发布方 post（管理端审核通过 / 驳回），投递 notify（结果通知作者）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminEventMessage implements Serializable {

    /** 帖子内部 id */
    private Long postId;

    /** 帖子作者内部 id（通知接收者） */
    private Long postUserId;

    /** 审核意见 / 驳回原因 */
    private String message;
}
