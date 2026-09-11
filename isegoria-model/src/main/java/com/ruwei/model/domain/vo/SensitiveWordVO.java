package com.ruwei.domain.vo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.util.Date;



@Data
public class SensitiveWordVO {

    private Long id;

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

    /**
     * 创建时间，由数据库默认值生成。
 * 列名 {@code createdAt} 与字段名一致
 * 插入/更新策略均设为 NEVER，交由数据库默认值维护，不在代码层赋值。
 */
@TableField(value = "createdAt", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createdAt;
}
