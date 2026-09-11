package com.ruwei.domain.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

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