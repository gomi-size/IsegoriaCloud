package com.ruwei.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 推荐流负反馈请求（「不感兴趣」等）。
 */
@Data
public class RecFeedbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 帖子内部 id
     */
    private Long postId;

    /**
     * 负反馈类型: 1不感兴趣 2内容质量差 3看过了 4其他
     */
    private Integer type;
}
