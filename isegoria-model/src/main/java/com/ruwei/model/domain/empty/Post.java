package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * 帖子/笔记表(推荐系统物料主表)
 * @TableName post
 */
@TableName(value ="post")
@Data
public class Post implements Serializable {
    /**
     * 
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 对外唯一编码
     */
    private String postCode;

    /**
     * 作者
     */
    private Long userId;

    /**
     * 所属板块
     */
    private Long boardId;

    /**
     * 标题
     */
    private String title;

    /**
     * 正文(发布时过敏感词 filter)
     */
    private String content;

    /**
     * 封面图URL
     */
    private String cover;

    /**
     * 1图文 2视频 3纯文
     */
    private Integer type;

    /**
     * 视频地址
     */
    private String videoUrl;

    /**
     * 话题（这里就是tag）
     */
    private String topic;

    /**
     * 1公开 2仅粉丝可见 3私密(仅作者)
     */
    private Integer visibility;

    /**
     * 生命周期状态: 1已发布 2草稿 3审核中 4下架
     */
    private Integer status;

    /**
     * 点赞数(热度公式×1)
     */
    private Integer likeCount;

    /**
     * 评论数(热度公式×2)
     */
    private Integer commentCount;

    /**
     * 收藏数(热度公式×3)
     */
    private Integer collectCount;

    /**
     * 浏览数(冷启动召回: viewCount<阈值)
     */
    private Integer viewCount;

    /**
     * 分享数
     */
    private Integer shareCount;

    /**
     * 拉踩(踩)数(热度公式负向降权, 权重默认-1)
     */
    private Integer dislikeCount;

    /**
     * 举报数(热度公式负向重扣, 权重默认-5)
     */
    private Integer reportCount;

    /**
     * 热度分 
     */
    private BigDecimal score;

    /**
     * 置顶(重排强插第1/2位)
     */
    private Integer isTop;

    /**
     * 精华
     */
    private Integer isEssence;

    /**
     * 审核结果: 1待审 2通过 3驳回
     */
    private Integer auditStatus;

    /**
     * 纬度
     */
    private BigDecimal latitude;

    /**
     * 经度
     */
    private BigDecimal longitude;

    /**
     * 位置名称
     */
    private String locationName;

    /**
     * 发布时间
     */
    private Date createdAt;

    /**
     * 
     */
    private Date updatedAt;

    /**
     * 逻辑删除: 0未删 1已删
     */
    @TableLogic
    private Integer isDelete;

    /**
     * 草稿来源帖 id（仅草稿记录使用；null 表示「新建」的草稿，非 null 表示由某篇正式帖编辑而来）。
     * 草稿槽位约束：同一用户对同一来源（draftOfId 相同，新建草稿为 null）最多一条草稿。
     */
    private Long draftOfId;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}