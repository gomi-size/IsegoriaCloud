package com.ruwei.domain.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 前端展示标签表类
 */

@Data
public class TagVO implements Serializable {

    /**
     * 标签主键（雪花 id，JSON 序列化为字符串）
     */
    private Long id;

    /**
     * 标签名(唯一)
     */
    private String name;

    /**
     * 使用次数（热门标签榜排序依据）
     */
    private Integer useCount;

    /**
     * 1正常 2禁用
     */
    private Integer status;


    private static final long serialVersionUID = 1L;
}