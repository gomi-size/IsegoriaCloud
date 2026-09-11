package com.ruwei.domain.dto;


import lombok.Data;

/**
 * 创建贴吧板块的请求类
 */
@Data
public class CreateBoardDTO {

    /**
     * 贴吧名字
     */
    private String name;

    /**
     * 贴吧唯一标识
     */
    private String slug;
    /**
     * 简洁
     */
    private String description;

    /**
     * 图标
     */
    private String icon;


}