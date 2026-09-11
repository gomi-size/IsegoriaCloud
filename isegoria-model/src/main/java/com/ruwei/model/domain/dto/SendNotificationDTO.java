package com.ruwei.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 发送通知的入参封装（用于 {@code NotificationService.sendNotification}）。
 *
 * <p>把"谁通知谁 / 什么类型 / 关联什么对象 / 文案 / 幂等键"集中在一个对象里传递，
 * 避免公共写入方法出现过长位置参数。由各业务事件监听器构造后调用。</p>
 */
@Data

public class SendNotificationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 接收者内部 id（通知谁）
     */
    private Long receiverId;

    /**
     * 触发者内部 id（谁触发的）
     */
    private Long senderId;

    /**
     * 通知类型：1点赞 2评论 3回复 4关注 5@提及 6系统 7收藏
     */
    private Integer type;

    /**
     * 目标类型：1帖子 2用户 3板块
     */
    private Integer targetType;

    /**
     * 关联对象内部 id（关注用户=被关注者id，关注板块=板块id）
     */
    private Long targetId;

    /**
     * 评论内部 id（评论/回复通知跳转锚点，前端据此定位楼中楼评论；其它类型可空）
     */
    private Long commentId;

    /**
     * 预览文案
     */
    private String content;

    /**
     * 业务幂等键（如 follow:{actor}:{followee}:{yyyyMMdd}），同一键只落库/推送一次
     */
    private String bizKey;
}
