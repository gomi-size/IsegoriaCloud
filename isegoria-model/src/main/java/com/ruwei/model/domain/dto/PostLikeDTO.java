package com.ruwei.model.domain.dto;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;

/**
 * 帖子点赞入参
 * @TableName post_like
 */
@Data
public class PostLikeDTO implements Serializable {


    /**
     * 帖子id
     */
    private Long postId;

    /**
     * 行为 0是点赞，1是取消点赞
     */
    private Integer status;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}