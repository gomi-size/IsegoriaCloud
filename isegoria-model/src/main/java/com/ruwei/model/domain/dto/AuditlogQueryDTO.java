package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审核日志分页查询条件（入参 DTO）。
 *
 * <p>用于管理端审核日志列表：id / adminId / targetType / targetId / action 精确匹配，
 * remark / createdAt 模糊匹配；均不传则查询全部，默认按创建时间倒序（最新在前）。
 * 分页/排序参数继承自 {@link PageRequest}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditlogQueryDTO extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 日志主键 id（精确匹配）
     */
    private Long id;

    /**
     * 操作管理员内部 id（精确匹配）
     */
    private Long adminId;

    /**
     * 审核对象类型（1帖子 2评论），精确匹配
     */
    private Integer targetType;

    /**
     * 审核对象内部 id（精确匹配）
     */
    private Long targetId;

    /**
     * 审核动作（1通过 2下架 3删除），精确匹配
     */
    private Integer action;

    /**
     * 审核备注（模糊匹配，支持输入部分内容）
     */
    private String remark;

    /**
     * 创建时间（模糊匹配，datetime 列 LIKE 匹配，传 "2026-08-05" 可查当天）
     */
    private String createdAt;
}
