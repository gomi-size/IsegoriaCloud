package com.ruwei.domain.dto;

import lombok.Data;

/**
 * 热度评分参数配置入参/出参（对应 score_config 单行配置）。
 *
 * <p>管理端 {@code PUT /admin/score-config} 整体更新；{@code GET} 返回当前生效值。
 * 权重允许为 0（表示该项不参与公式）；{@link #tauHours} 必须 &gt; 0（防除零），
 * 由 Service/Manager 层通过 {@code ThrowUtils} 校验（项目不启用 Bean Validation）。</p>
 */
@Data
public class ScoreConfigDTO {

    /**
     * 点赞权重（正向）
     */
    private Double likeW;

    /**
     * 评论权重（正向）
     */
    private Double commentW;

    /**
     * 收藏权重（正向）
     */
    private Double collectW;

    /**
     * 分享权重（正向）
     */
    private Double shareW;

    /**
     * 拉踩(踩)权重（负向降权，通常为负值）
     */
    private Double dislikeW;

    /**
     * 举报权重（负向重扣，通常为负值）
     */
    private Double reportW;

    /**
     * 时间衰减半衰期(小时)，必须 > 0
     */
    private Double tauHours;
}
