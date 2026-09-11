package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 热度评分参数配置实体，对应数据库表 {@code score_config}。
 *
 * <p>该表是<b>单行配置表</b>：5 分钟一次的 {@code ScoreRecalcJob} 通过
 * {@code ScoreConfigManager} 内存缓存读取本表参数计算帖子热度分，管理端
 * 修改后热刷新（refresh），无需改 yaml / 重启。</p>
 *
 * <p>权重语义：{@link #likeW}/{@link #commentW}/{@link #collectW}/{@link #shareW}
 * 为正向加分项；{@link #dislikeW}（拉踩）/{@link #reportW}（举报）为负向降权项，
 * 通常配置为负数；{@link #tauHours} 为时间衰减半衰期（小时），必须 &gt; 0（防除零）。</p>
 *
 * @TableName score_config
 */
@TableName("score_config")
@Data
public class ScoreConfig {

    /**
     * 自增主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 点赞权重（正向加分项）
     */
    private Double likeW;

    /**
     * 评论权重（正向加分项）
     */
    private Double commentW;

    /**
     * 收藏权重（正向加分项）
     */
    private Double collectW;

    /**
     * 分享权重（正向加分项）
     */
    private Double shareW;

    /**
     * 拉踩(踩)权重（负向降权项，通常为负值）
     */
    private Double dislikeW;

    /**
     * 举报权重（负向重扣项，通常为负值）
     */
    private Double reportW;

    /**
     * 时间衰减半衰期(小时)，必须 &gt; 0（防除零）
     */
    private Double tauHours;

    /**
     * 修改时间，由数据库默认值生成。
     * 列名 {@code updatedAt} 与字段名一致；插入/更新策略均设为 NEVER，交由数据库维护。
     */
    @TableField(value = "updatedAt", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updatedAt;
}
