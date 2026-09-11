package com.ruwei.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 帖子<b>列表</b>展示 VO（简洁卡片，供帖子列表页 / 首页信息流使用）。
 *
 * <p><b>与 {@link PostVO}（详情）的分工</b>：列表只给前端渲染卡片所需的轻量字段
 * （标题、封面、正文摘要、作者昵称/头像、板块、计数、时间等），<b>不含完整正文 content /
 * 图片全列表 imageUrl / 话题 topic / 可见性/状态等详情信息</b>；用户点击进入帖子后，
 * 由详情接口 {@code GET /post/{id}} 返回完整的 {@link PostVO}。</p>
 *
 * <p>作者信息（{@code userNickname} / {@code userAvatar}）由 Service 层批量查 user 表装配，
 * 板块信息（{@code boardName} / {@code boardSlug}）由 {@code BoardBriefFiller} 批量查 board
 * 表装配，均不冗余存储于 post 表。</p>
 */
@Data
public class PostBrowseVO implements Serializable {

    /** 帖子内部主键（雪花 id，JSON 输出为字符串，防前端丢精度） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 对外唯一编码（P 前缀，Redis 原子自增）
     */
    private String postCode;

    /**
     * 作者内部 id（雪花 id，JSON 输出为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 作者昵称（来自 user 表 nickname）
     */
    private String userNickname;

    /**
     * 作者头像 URL（来自 user 表 avatar）
     */
    private String userAvatar;

    /**
     * 所属板块内部主键（post.boardId，可空=无板块帖；JSON 输出为字符串，防前端丢精度）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long boardId;

    /**
     * 所属板块名（来自 board 表 name，由 BoardBriefFiller 批量装配；
     * 无板块或板块已逻辑删除时为 null，前端不渲染板块入口）
     */
    private String boardName;

    /**
     * 所属板块唯一标识（来自 board 表 slug，前端拼 {@code /board/{slug}} 跳转板块页）
     */
    private String boardSlug;

    /**
     * 标题
     */
    private String title;

    /**
     * 封面图URL
     */
    private String cover;

    /**
     * 预览正文（正文前 N 字符截断的摘要，供列表卡片两行内展示；
     * 由 Service 层查询时截取填充，不冗余存储于 post 表）
     */
    private String contentPreview;

    /**
     * 内容形态: 1图文 2视频 3纯文
     */
    private Integer type;

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
     *是否点赞
     */
    private Boolean isLiked;

    /**
     * 是否已收藏（当前登录用户，列表装配 fillIsCollected 填充）
     */
    private Boolean isCollected;

    /**
     * 浏览数
     */
    private Integer viewCount;

    /**
     * 置顶(重排强插第1/2位)
     */
    private Integer isTop;

    /**
     * 精华
     */
    private Integer isEssence;

    /**
     * 发布时间
     */
    private Date createdAt;

    private static final long serialVersionUID = 1L;
}
