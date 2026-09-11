package com.ruwei.domain.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 作者/用户瘦身展示对象（对齐 docs/modules/10-comment-module.md §4.2 的 AuthorMiniVO）。
 *
 * <p>用于评论列表等偏公开界面内嵌展示"这是谁"（id / 对外编码 / 昵称 / 头像），
 * 不携带 phone / email / admin / 各类计数等敏感或冗余数据。</p>
 */
@Data
public class AuthorMiniVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户内部 id（= Sa-Token loginId）
     */
    private Long id;

    /**
     * 对外展示的唯一编码（前端用于拼接主页链接，如 /user/{userId}）
     */
    private Long userId;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像 URL
     */
    private String avatar;
}
