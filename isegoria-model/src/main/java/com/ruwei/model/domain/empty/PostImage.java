package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import lombok.Data;

/**
 * 帖子图片表
 * @TableName post_image
 */
@TableName(value ="post_image")
@Data
public class PostImage implements Serializable {
    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属帖子内部id
     */
    private Long postId;

    /**
     * 图片URL
     */
    private String url;

    /**
     * 图片宽度(px)
     */
    private Integer width;

    /**
     * 图片高度(px)
     */
    private Integer height;

    /**
     * 排序序号(升序，封面取 sort 最小)
     */
    private Integer sort;

    /**
     * 生命周期状态: 1已发布 2草稿 3审核中 4下架
     */
    private Integer status;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}