package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 评论点赞表(toggle 幂等, 唯一键 ukCommentUser 兜底)
 * @TableName comment_like
 */
@TableName(value ="comment_like")
@Data
public class CommentLike implements Serializable {
    /**
     * 主键(雪花)
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 评论内部id
     */
    private Long commentId;

    /**
     * 点赞者内部id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createdAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
