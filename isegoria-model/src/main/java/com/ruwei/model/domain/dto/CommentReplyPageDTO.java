package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 某顶层评论的全部回复分页查询条件（对齐 docs/modules/10-comment-module.md §4.1）。
 *
 * <p>盖楼语义固定按创建时间正序，不开放 sortField 排序。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommentReplyPageDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 必填, 顶层评论id（二级回复一律指向顶层评论，含楼中楼互评）
     */
    private Long commentId;
}
