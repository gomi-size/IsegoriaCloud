package com.ruwei.domain.dto;

import lombok.Data;


@Data
public class SensitiveWordDTO {


    /**
     * 敏感词内容（唯一约束 {@code uk_word} 防重）
     */
    private String word;

    /**
     * 分类（仅用于后台筛选/统计，不参与匹配算法）：
     * 1=违禁 2=广告 3=辱骂 ……可按业务自行扩展
     */
    private Integer category;

    /**
     * 处置动作，决定命中该词后的行为：
     * 1=替换成 *** 后发布 2=直接拦截（拒绝发布）3=进入审核队列
     */
    private Integer action;

}
