package com.ruwei.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 通知表(历史存储/消息中心真相源)
 * @TableName notification
 */
@TableName(value ="notification")
@Data
public class NotificationVO implements Serializable {

    /**
     * 方便查询
      */
    private Long id;

    /**
     * 触发者内部 id（= notification.senderId，保留作引用 / 前端按需使用）
     */
    private Long senderId;

    /**
     * 触发者展示信息（瘦身版，见 {@link SenderVO}）。
     * 由后端在查询时按 senderId 批量补全，前端无需再单独拉取。
     */
    private SenderVO sender;

    /**
     * 1点赞 2评论 3回复 4关注 5@提及 6系统 7收藏 8转发
     */
    private Integer type;

    /**
     * 目标类型：1帖子 2用户 3板块（板块关注通知走 3）
     */
    private Integer targetType;

    /**
     * 预览文案
     */
    private String content;

    /**
     * 评论内部 id（评论/回复通知跳转锚点，前端据此定位楼中楼评论）
     */
    private Long commentId;

    /**
     * 0未读 1已读
     */
    private Integer isRead;

    /**
     * 
     */
    private Date createdAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}