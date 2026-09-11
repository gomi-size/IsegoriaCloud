package com.ruwei.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 帖子结构化内容块（图文混排载体）。
 *
 * <p>帖子正文 {@code Post.content} 在新数据中存储为本类的 JSON 数组（有序），
 * 前端按数组顺序渲染即可实现「文字与图片交错排列」；旧数据（纯文本 + post_image 图集）
 * 由 {@code PostServiceImpl#buildPostVO} 兼容合成为等价 blocks，前端无需区分新旧帖。</p>
 *
 * <p>块类型 type：{@code p} 段落 / {@code heading} 小标题 / {@code image} 图片 / {@code video} 视频。</p>
 */
@Data
public class ContentBlock implements Serializable {

    /**
     * 块类型：p / heading / image / video
     */
    private String type;

    /**
     * 文本内容（p / heading 使用）
     */
    private String text;

    /**
     * 资源地址（image / video 使用）
     */
    private String url;

    /**
     * 图片原始宽度 px（前端渲染时配合 height 固定宽高比，防止 CLS 抖动）
     */
    private Integer w;

    /**
     * 图片原始高度 px
     */
    private Integer h;

    /**
     * 无障碍替代文本（image 使用，alt）
     */
    private String alt;

    private static final long serialVersionUID = 1L;
}
