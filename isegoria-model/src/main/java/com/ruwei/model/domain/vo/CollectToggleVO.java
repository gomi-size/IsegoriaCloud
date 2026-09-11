package com.ruwei.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 收藏 toggle 返回：当前是否已收藏 + 最新收藏总数（DB 直写，取自 post.collectCount）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CollectToggleVO implements Serializable {
    private static final long serialVersionUID = 1L;
    /** 切换后是否处于「已收藏」状态 */
    private Boolean isCollected;
    /** 切换后收藏总数 */
    private Integer collectCount;
}
