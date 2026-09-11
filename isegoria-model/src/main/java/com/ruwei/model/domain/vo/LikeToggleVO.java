package com.ruwei.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 点赞 toggle 返回：当前是否赞 + 当前计数（均来自 Redis，毫秒级）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LikeToggleVO implements Serializable {
    private static final long serialVersionUID = 1L;
    /** 切换后是否处于「已赞」状态 */
    private Boolean isLiked;
    /** 切换后点赞总数 */
    private Long likeCount;
}