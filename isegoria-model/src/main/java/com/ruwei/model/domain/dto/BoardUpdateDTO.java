package com.ruwei.domain.dto;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;

/**
 * 更改贴吧请求类
 * @TableName board
 */
@Data
public class BoardUpdateDTO implements Serializable {
    /**
     * 
     */
    private Long id;

    /**
     * 吧名
     */
    private String name;

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



    private static final long serialVersionUID = 1L;
}