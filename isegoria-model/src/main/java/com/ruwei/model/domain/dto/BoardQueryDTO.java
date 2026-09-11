package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 贴吧板块查询条件（入参 DTO）。
 *
 * <p>用于板块列表搜索，配合 {@code QueryWrapperUtils.getBoardQueryWrapper} 使用。
 * 分页/排序参数继承自 {@link PageRequest}（默认按关注数倒序）。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *     <li>{@link #name}：吧名，模糊匹配</li>
 *     <li>{@link #slug}：唯一标识，模糊匹配</li>
 *     <li>{@link #description}：简介，模糊匹配</li>
 *     <li>{@link #creatorId}：创建者/吧主内部 id，模糊匹配</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BoardQueryDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 吧名，模糊匹配
     */
    private String name;

    /**
     * 唯一标识，模糊匹配
     */
    private String slug;

    /**
     * 简介，模糊匹配
     */
    private String description;

    /**
     * 创建者/吧主内部 id，模糊匹配
     */
    private Long creatorId;
}
