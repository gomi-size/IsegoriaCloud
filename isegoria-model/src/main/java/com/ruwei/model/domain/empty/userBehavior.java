package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 用户行为日志
 * @TableName userbehavior
 */
@TableName(value ="userBehavior")
@Data
public class userBehavior implements Serializable {
    /**
     * 主键(雪花 ASSIGN_ID)
     */
    @TableId
    private Long id;

    /**
     * 用户内部id
     */
    private Long userId;

    /**
     * 帖子内部id
     */
    private Long postId;

    /**
     * 1曝光 2点击进入 3浏览/停留 4点赞 5评论 6收藏 7分享 8负反馈
     */
    private Integer action;

    /**
     * 来源: 1推荐流 2关注流 3板块流 4搜索 5热榜 0未知
     */
    private Integer source;

    /**
     * 信息流展示位次(第几条, 用于去偏)
     */
    private Integer position;

    /**
     * 停留时长(秒), action=3 有效
     */
    private Integer dwellSec;

    /**
     * 上下文JSON: {net,hour,city}
     */
    private String extras;

    /**
     * 行为时间
     */
    private Date createdAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}