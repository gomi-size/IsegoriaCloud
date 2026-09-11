package com.ruwei.domain.dto;

import lombok.Data;

/**
 * 新增敏感词的请求体（入参 DTO）。
 *
 * <p>用于管理端 {@code POST /admin/sensitive-words} 接口，既支持单个对象也支持对象数组
 * （批量一次性传多组）。{@link #category} 与 {@link #action} 均有默认值，调用方可按需覆盖。</p>
 */
@Data
public class SensitiveWordAddDTO {
    /**
     * 敏感词内容（必填）
     */
    private String word;

    /**
     * 分类：1=违禁 2=广告 3=辱骂 ……（仅用于后台筛选/统计），默认 1
     */
    private Integer category = 1;

    /**
     * 处置动作：1=替换 2=拦截 3=进入审核，默认 1（替换）
     */
    private Integer action = 1;
}
