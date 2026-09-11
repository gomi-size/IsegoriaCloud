package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 评论表(两级盖楼)
 * @TableName comment
 */
@TableName(value ="comment")
@Data
public class Comment implements Serializable {
    /**
     * 主键(代码层雪花ASSIGN_ID, DB自增仅兜底)
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 帖子内部id
     */
    private Long postId;

    /**
     * 评论者内部id
     */
    private Long userId;

    /**
     * 父评论id: 0=一级评论, 二级回复一律指向顶层评论(含楼中楼互评)
     */
    private Long parentId;

    /**
     * 被回复用户内部id
     */
    private Long replyToUserId;

    /**
     * 评论内容(发布时过敏感词 filter: 替换则存替换后文本, 拦截则拒绝)
     */
    private String content;

    /**
     * 点赞数(CountUtils原子增减)
     */
    private Integer likeCount;

    /**
     * 子回复数(仅顶层评论有意义, 只统计status=1)
     */
    private Integer replyCount;

    /**
     * 1正常 2已删除(软删, 列表需展示"已删除"占位)
     */
    private Integer status;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 修改时间
     */
    private Date updatedAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}