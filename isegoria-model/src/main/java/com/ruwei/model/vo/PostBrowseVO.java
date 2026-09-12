package com.ruwei.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.Tag;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 帖子<b>列表</b>展示 VO（简洁卡片，供帖子列表页 / 首页信息流使用）。
 *
 * <p><b>与 {@link PostVO}（详情）的分工</b>：列表只给前端渲染卡片所需的轻量字段
 * （标题、封面、正文摘要、作者昵称/头像、板块、标签、计数、时间等），<b>不含完整正文 content /
 * 图片全列表 imageUrl / 可见性/状态等详情信息</b>；用户点击进入帖子后，
 * 由详情接口 {@code GET /post/{id}} 返回完整的 {@link PostVO}。</p>
 *
 * <p>作者信息（{@code userNickname} / {@code userAvatar}）由 Service 层批量查 user 表装配，
 * 板块信息（{@link #board}，含 id/name/slug/icon）由 {@code BoardBriefFiller} 批量查 board
 * 表装配，标签信息（{@link #tags}，含 id/name）由 {@code TagBriefFiller} 批量查 post_tag + tag
 * 表装配，均不冗余存储于 post 表。</p>
 *
 * <p><b>可跳转设计</b>：板块与标签采用<b>内部静态类</b> {@link BoardBrief} / {@link TagBrief}
 * 承载，前端直接拿整个对象渲染 chip 并跳转，不必在卡片上平铺一堆 {@code boardXxx} / {@code tagXxx}
 * 字段：
 * <ul>
 *   <li>板块：{@code board.slug} → {@code /board/{slug}}（slug 语义化、稳定），
 *       {@code board.id} 仍保留供 {@code /post/list?boardId=} 等按 id 查询的接口使用；</li>
 *   <li>标签：{@code tag.id} → {@code /tag/{id}}（tag 表无 slug，按 id 跳转），
 *       {@code tag.name} 直接作为 chip 文案。</li>
 * </ul>
 * 两者内部的雪花 id 同样用 {@code ToStringSerializer} 输出字符串，防 JS 精度丢失。</p>
 *
 * @author ruwei
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
     * 所属板块简要信息（内部类，来自 board 表，由 {@code BoardBriefFiller} 批量装配）。
     *
     * <p>前端直接用 {@code board.name} 渲染 chip、{@code board.slug} 拼
     * {@code /board/{slug}} 跳转板块页；无板块或板块已逻辑删除时为 null，前端不渲染板块入口。</p>
     */
    private BoardBrief board;

    /**
     * 话题标签列表（内部类，来自 post_tag + tag 表，由 {@code TagBriefFiller} 批量装配）。
     *
     * <p>前端用 {@code tag.name} 渲染 chip、{@code tag.id} 拼 {@code /tag/{id}} 跳转话题页；
     * 无标签时为空列表 {@code []}（<b>绝不为 null</b>，前端可直接 v-for）。
     * 卡片空间有限，建议前端只展示前 2 个。</p>
     */
    private List<TagBrief> tags;

    /**
     * 所属板块名（来自 board 表 name）。
     *
     * <p><b>已废弃的平铺字段</b>：仅为兼容存量前端（PostCard / FeedCard / RankRow / LatestRow
     * 直接读 {@code boardName}）保留，与 {@link #board} 由 {@code BoardBriefFiller} 同时填充。
     * 新代码一律使用 {@code board.name}，待前端迁移完成后移除本字段。</p>
     */
    private String boardName;

    /**
     * 所属板块唯一标识（来自 board 表 slug，前端拼 {@code /board/{slug}} 跳转板块页）。
     *
     * <p><b>已废弃的平铺字段</b>：兼容存量前端，与 {@link #board} 同时填充，
     * 新代码一律使用 {@code board.slug}。</p>
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

    // ==================== 内部类：可跳转的关联对象 ====================

    /**
     * 板块简要信息（列表卡片上的板块 chip，支持跳转板块页）。
     *
     * <p><b>跳转约定</b>：优先用 {@code slug} 拼 {@code /board/{slug}}（语义化、可缓存、SEO 友好）；
     * {@code id} 仅用于按 id 过滤的接口（如 {@code /post/list?boardId=}）与前端本地判重。
     * 两者都是雪花 id/唯一标识，均来自 board 表，不冗余存储于 post 表。</p>
     */
    @Data
    public static class BoardBrief implements Serializable {

        /** 板块内部主键（雪花 id，JSON 输出为字符串，防前端丢精度） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        /** 板块名（board.name，chip 文案） */
        private String name;

        /** 板块唯一标识（board.slug，前端拼 /board/{slug} 跳转） */
        private String slug;

        /** 板块图标 URL（board.icon，可空；前端可降级为纯文字 chip） */
        private String icon;

        private static final long serialVersionUID = 1L;

        /**
         * 由板块实体构造简要信息（供装配器批量调用）。
         *
         * @param board 板块实体（null 返回 null）
         * @return 板块简要信息
         */
        public static BoardBrief of(Board board) {
            if (board == null) {
                return null;
            }
            BoardBrief brief = new BoardBrief();
            brief.setId(board.getId());
            brief.setName(board.getName());
            brief.setSlug(board.getSlug());
            brief.setIcon(board.getIcon());
            return brief;
        }
    }

    /**
     * 话题标签简要信息（列表卡片上的标签 chip，支持跳转话题页）。
     *
     * <p><b>跳转约定</b>：tag 表目前只有 id + name（<b>无 slug</b>），故统一按
     * {@code /tag/{id}} 跳转；后续若给 tag 增加 slug 字段，只需在本类加一个字段，
     * 前端路由无需改动其它结构。</p>
     *
     * <p><b>与详情 {@code PostVO.topic}（{@link TagVO}）的区别</b>：详情带 useCount / status
     * 等运营字段，卡片只需 id + name，保持列表响应体精简。</p>
     */
    @Data
    public static class TagBrief implements Serializable {

        /** 标签内部主键（雪花 id，JSON 输出为字符串，防前端丢精度） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        /** 标签名（tag.name，chip 文案，不含 # 前缀，前端自行拼 #） */
        private String name;

        private static final long serialVersionUID = 1L;

        /**
         * 由标签实体构造简要信息（供装配器批量调用）。
         *
         * @param tag 标签实体（null 返回 null）
         * @return 标签简要信息
         */
        public static TagBrief of(Tag tag) {
            if (tag == null) {
                return null;
            }
            TagBrief brief = new TagBrief();
            brief.setId(tag.getId());
            brief.setName(tag.getName());
            return brief;
        }
    }
}
