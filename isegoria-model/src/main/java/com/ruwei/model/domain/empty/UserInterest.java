
package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * 用户长期兴趣画像
 * @TableName user_interest
 */
@TableName(value = "user_interest")
@Data
public class UserInterest implements Serializable {
    /**
     * 主键(雪花 ASSIGN_ID)
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户内部id(=loginId)
     */
    private Long userId;

    /**
     * 1话题 2标签 3类型 4板块 5作者（Phase 1 实际启用 2/3/4/5，dim=1 预留）
     */
    private Integer dimension;

    /**
     * 兴趣维度值：tagId / type码(1图文2视频3纯文) / boardId / authorId
     */
    private String value;

    /**
     * 兴趣权重(0~1+, 越大越感兴趣)
     */
    private BigDecimal weight;

    /**
     * 最近一次强化时间
     */
    private Date lastActiveAt;

    /**
     * 
     */
    private Date createdAt;

    /**
     * 
     */
    private Date updatedAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}