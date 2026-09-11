package com.ruwei.domain.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 图片上传结果：前端可直接用 url 填充 ContentBlock，并配合 width/height 固定宽高比。
 */
@Data
public class ImageUploadVO implements Serializable {

    /**
     * 展示用 URL（默认返回 webp 压缩图）
     */
    private String url;

    /**
     * 图片宽度 px
     */
    private Integer width;

    /**
     * 图片高度 px
     */
    private Integer height;

    /**
     * 文件大小字节
     */
    private Long size;

    /**
     * 图片 MIME 类型
     */
    private String mime;

    private static final long serialVersionUID = 1L;
}
