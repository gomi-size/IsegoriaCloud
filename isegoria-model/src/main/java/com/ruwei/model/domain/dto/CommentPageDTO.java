package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 帖子评论列表分页查询条件（一级评论分页，对齐 docs/modules/10-comment-module.md §4.1）。
 *
 * <p>盖楼语义固定按创建时间正序（楼层顺序不可乱），不开放 sortField 排序。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommentPageDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 必填, 帖子对外编码(post.postCode, 如 P100001) → Service 层解析内部 postId
     */
    private String postCode;
}
