package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 审核日志表
 * @TableName auditlog
 */
@TableName(value ="auditLog")
@Data
public class Auditlog implements Serializable {
    /**
     * 
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 
     */
    private Long adminId;

    /**
     * 1帖子 2评论
     */
    private Integer targetType;

    /**
     * 
     */
    private Long targetId;

    /**
     * 1通过 2下架 3删除
     */
    private Integer action;

    /**
     * 审核操作的补充说明，例如拒绝原因、下架理由等
     */
    private String remark;

    /**
     * 
     */
    private Date createdAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}