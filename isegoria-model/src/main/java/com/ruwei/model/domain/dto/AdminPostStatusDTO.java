package com.ruwei.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员强制设置帖子状态（status / visibility）的入参。
 *
 * <p>与普通用户接口不同（PostDTO 传中文文字），管理端直接传<b>枚举码</b>：
 * <ul>
 *   <li>status：1已发布 2草稿 3审核中 4下架（对应 {@code PostStatusEnum}）；</li>
 *   <li>visibility：1公开 2仅粉丝可见 3私密（对应 {@code PostVisibilityEnum}）。</li>
 * </ul>
 * 两个字段<b>至少传一个</b>，只更新传入的字段；status 变化时会联动调整
 * user/board 的 postCount（仅「已发布」口径）、同步 auditStatus、迁移图片/标签版本。</p>
 */
@Data
public class AdminPostStatusDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 帖子内部 id（必传）
     */
    private Long postId;

    /**
     * 生命周期状态码：1已发布 2草稿 3审核中 4下架（可选，不传则不修改）
     */
    private Integer status;

    /**
     * 可见性码：1公开 2仅粉丝可见 3私密（可选，不传则不修改）
     */
    private Integer visibility;

}
