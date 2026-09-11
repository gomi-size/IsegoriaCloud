package com.ruwei.model.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 关注关系表
 * @TableName userFollow
 */
@TableName(value ="user_follow")
@Data
public class UserFollow {
    /**
     * 
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 主动关注者
     */
    private Long followerId;

    /**
     * 被关注者
     */
    private Long followeeId;

    /**
     * 1关注 2已取消关注
     */
    private Integer status;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 修改时间
     */
    private Date updatedAt;
}