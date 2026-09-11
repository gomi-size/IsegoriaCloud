package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 帖子分页查询条件（入参 DTO）。
 *
 * <p>用于帖子列表页：可查自己的帖子、也可查别人的帖子；
 * 字符串字段（postCode / title / createdAt）模糊匹配，id 类字段（id / boardId / userId）精确匹配；
 * <b>visibility / status 传中文文字经枚举转整数精确匹配，为空则查询所有</b>；
 * 均不传则查询全部，默认按创建时间倒序（最新在前）。分页/排序参数继承自 {@link PageRequest}。</p>
 *
 * <p><b>visibility / status 一般只在「我的主页」场景传递</b>（配合查询条件 userId=自己，
 * 此时列表放行全部状态）；查询他人或全部时由后端强制只返回「已发布」，不受这两个字段影响。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostQueryDTO extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 帖子内部主键（精确匹配）
     */
    private Long postId;

    /**
     * 对外唯一编码（模糊匹配，支持输入部分编码）
     */
    private String postCode;

    /**
     * 所属板块内部 id（精确匹配）
     */
    private Long boardId;

    /**
     * 标题（模糊匹配）
     */
    private String title;

    /**
     * 作者内部 id（精确匹配；查他人时只返回已发布帖子）
     */
    private Long userId;

    /**
     * 创建时间（模糊匹配，datetime 列 LIKE 匹配，传 "2026-08-05" 可查当天）
     */
    private String createdAt;

    //前端传递，只在自己主页的时候传递
    /**
     * 可见性文字: 公开 / 仅粉丝可见 / 私密
     * （对应 {@code PostVisibilityEnum}）
     */
    private String visibility;

    /**
     * 可见性文字列表（多选 IN 查询）：用于「公开 或 仅粉丝可见」这类场景，
     * 例如关注流只展示 公开 / 仅粉丝可见 且已发布的稿件。
     * 与 {@link #visibility} 互斥：本字段非空时优先按列表 IN 匹配，忽略单值 {@code visibility}。
     */
    private List<String> visibilityList;

    /**
     * 生命周期状态文字: 已发布 / 草稿 / 审核中 / 下架
     * （对应 {@code PostStatusEnum}）
     */
    private String status;

}
