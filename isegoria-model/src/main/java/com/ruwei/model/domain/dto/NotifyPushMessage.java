package com.ruwei.domain.dto;

import lombok.Data;

import java.io.Serializable;


/** 经 WebSocket 实时推到前端的通知消息体（轻量，不暴露整条 Notification） */
@Data
public class NotifyPushMessage implements Serializable {
    /**
     * 对应 notification.id，前端去重用
     */
    private Long notificationId;
    /**
     * 接收者内部 id
     */
    private Long receiverId;
    /**
     * 1点赞 2评论 3回复 4关注 5@提及 6系统 7收藏 8转发
     */
    private Integer type;
    /**
     * 触发者内部 id
     */
    private Long senderId;
    /**
     * 1帖子 2用户 3板块
     */
    private Integer targetType;
    /**
     * 关联对象 id
     */
    private Long targetId;

    /**
     * 评论内部 id（评论/回复通知跳转锚点，前端据此定位楼中楼评论）
     */
    private Long commentId;
    /**
     * 预览文案
     */
    private String content;
    /**
     * 毫秒时间戳
     */
    private Long createdAt;
}