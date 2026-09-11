package com.ruwei.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 评论展示对象（对齐 docs/modules/10-comment-module.md §4.2）。
 *
 * <p>JSON 小写契约；user / replyToUser 为瘦身后的 AuthorMiniVO；
 * status=2 时前端据此渲染「该评论已删除」占位；replies 仅列表接口携带（前 2 条二级回复）。</p>
 */
@Data
public class CommentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 评论内部 id
     */
    private Long id;

    /**
     * 帖子对外编码（组装时批查 post 带出）
     */
    private String postCode;

    /**
     * 评论者（可空：用户已注销/删除时置 null，由前端兜底展示）
     */
    private AuthorMiniVO user;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 父评论 id：0=一级评论，二级回复一律指向顶层评论
     */
    private Long parentId;

    /**
     * 被回复者（仅二级回复有意义，可空）
     */
    private AuthorMiniVO replyToUser;

    /**
     * 点赞数
     */
    private Integer likeCount;

    /**
     * 子回复数（仅顶层评论有意义）
     */
    private Integer replyCount;

    /**
     * 当前用户是否赞过
     */
    private Boolean isLiked;

    /**
     * 1正常 2已删除（前端据此渲染「已删除」占位）
     */
    private Integer status;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 仅列表接口：前 2 条二级回复（可空）
     */
    private List<CommentVO> replies;
}
