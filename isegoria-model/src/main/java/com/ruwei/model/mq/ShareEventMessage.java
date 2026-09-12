package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分享事件消息（替代旧单体本地事件 {@code ShareEvent}）。
 *
 * <p>routing key {@code evt.share}：发布方 post（{@code PostServiceImpl.shareToUser}），
 * 投递 notify（分享通知）与 rec（兴趣画像）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShareEventMessage implements Serializable {

    /** 分享者内部 id（=loginId） */
    private Long loginId;

    /** 被分享的帖子内部 id */
    private Long postId;

    /** 接收者内部 id（通知接收者） */
    private Long targetUserId;
}
