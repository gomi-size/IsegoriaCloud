package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户关注事件消息（替代旧单体本地事件 {@code FollowEvent}）。
 *
 * <p>routing key {@code evt.follow}：发布方 social，投递 notify（关注通知）。
 * 取关（{@link #ACTION_CANCEL}）是否发通知以旧 {@code FollowUserEventListener} 实际逻辑为准（Phase 6.2）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowEventMessage implements Serializable {

    /** 关注动作：关注 */
    public static final String ACTION_FOLLOW = "FOLLOW";

    /** 关注动作：取关 */
    public static final String ACTION_CANCEL = "CANCEL";

    /** 发起者内部 id（=loginId） */
    private Long loginId;

    /** 被关注者内部 id */
    private Long targetId;

    /** 动作：{@link #ACTION_FOLLOW} / {@link #ACTION_CANCEL} */
    private String action;
}
