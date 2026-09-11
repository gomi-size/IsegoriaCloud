package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 用户关注板块表
 * @TableName board_follow
 */
@TableName(value ="board_follow")
@Data
public class BoardFollow implements Serializable {
    /**
     * 
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 关注者的Id
     */
    private Long userId;

    /**
     * 板块的Id
     */
    private Long boardId;

    /**
     * 关注状态：1-关注 2-已取消关注（软标记保留历史，对齐 user_follow.status 语义）
     */
    private Integer status;

    /**
     * 关注时间
     */
    private Date createdAt;

    @Serial
    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}