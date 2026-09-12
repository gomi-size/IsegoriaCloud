package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 板块关注事件消息（替代旧单体本地事件 {@code BoardFollowEvent}）。
 *
 * <p>routing key {@code evt.boardfollow}：发布方 social，投递 notify（通知吧主）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BoardFollowEventMessage implements Serializable {

    /** 关注者内部 id（=loginId） */
    private Long loginId;

    /** 板块内部 id */
    private Long boardId;

    /** 板块创建者内部 id（通知接收者 / 吧主） */
    private Long boardCreatorId;
}
