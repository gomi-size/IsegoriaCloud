package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 通知表(历史存储/消息中心真相源)
 * @TableName notification
 */
@TableName(value ="notification")
@Data
public class Notification implements Serializable {
    /**
     * 
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 接收者
     */
    private Long receiverId;

    /**
     * 触发者
     */
    private Long senderId;

    /**
     * 1点赞 2评论 3回复 4关注 5@提及 6系统 7收藏
     */
    private Integer type;

    /**
     * 1帖子 2用户 3板块
     * （2=用户：targetId 指向被关注者主页；3=板块：targetId 指向板块主页）
     */
    private Integer targetType;

    /**
     * 关联对象内部 id（关注用户=被关注者内部 id；关注板块=板块内部 id）
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
     * 业务幂等键(如 like:{uid}:{postId}), 防重复通知
     */
    private String bizKey;

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