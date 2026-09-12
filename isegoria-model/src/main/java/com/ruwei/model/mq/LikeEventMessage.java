package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 点赞事件消息（替代旧单体本地事件 {@code LikeEvent}）。
 *
 * <p>routing key {@code evt.like}：发布方 interaction（帖子赞 / 评论赞），
 * 一条消息同时投递到 notify（站内通知）与 rec（兴趣画像）两个队列。</p>
 *
 * <p>来源：旧仓库 {@code component/notification/event/LikeEvent}，
 * 去掉 {@code ApplicationEvent} 的 source 参数，构造顺序保持一致。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeEventMessage implements Serializable {

    /** 点赞者内部 id（=loginId） */
    private Long actorId;

    /** 帖子内部 id（评论点赞场景时为评论所属帖子的 id） */
    private Long postId;

    /** 被赞者内部 id（帖子作者） */
    private Long postUserId;
}
