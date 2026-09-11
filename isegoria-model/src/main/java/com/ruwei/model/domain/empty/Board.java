package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 贴吧板块表
 * @TableName board
 */
@TableName(value ="board")
@Data
public class Board implements Serializable {
    /**
     * 
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 吧名
     */
    private String name;

    /**
     * 唯一标识
     */
    private String slug;

    /**
     * 简介
     */
    private String description;

    /**
     * 图标
     */
    private String icon;

    /**
     * 创建者/吧主
     */
    private Long creatorId;

    /**
     * 关注数
     */
    private Integer followCount;

    /**
     * 帖子数
     */
    private Integer postCount;

    /**
     * 等级头衔规则(JSON)
     */
    private String levelRule;

    /**
     * 1正常 2封禁
     */
    private Integer status;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

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