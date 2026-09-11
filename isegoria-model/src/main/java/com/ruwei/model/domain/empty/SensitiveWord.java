package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 敏感词实体，对应数据库表 {@code sensitiveWord}。
 *
 * <p>该表是敏感词过滤器的<b>数据源</b>（而非被业务频繁查询的表）：
 * 过滤器在启动及管理端增删词后，会把全表按 {@link #action} 拆成三棵内存 DFA Trie 进行匹配。
 * 其中 {@link #action} 是唯一驱动匹配后处置行为的字段；
 * {@link #category} 仅作为后台筛选/统计用的元数据，<b>不参与</b>匹配算法。</p>
 *
 * @TableName sensitiveWord
 */
@TableName("sensitive_word")
@Data
public class SensitiveWord {

    /**
     * 自增主键
     */
    @TableId(type = IdType.ASSIGN_ID)
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
