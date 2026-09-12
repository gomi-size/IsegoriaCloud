package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 浏览事件消息（替代旧单体本地事件 {@code ViewEvent}）。
 *
 * <p>routing key {@code evt.view}：发布方 post（浏览历史 record 之后），仅投递 rec（行为信号 + 兴趣画像）。</p>
 *
 * <p><b>待核对</b>：本类字段按手册 §2.5 的「userId, postId, dwellMs(若有) + 保留原 ViewEvent 构造字段」
 * 建模，Phase 4 改造 {@code PostServiceImpl:1582} 时需与旧仓库 {@code ViewEvent} 逐个构造参数对齐，
 * 缺字段在此补全（消费方 rec 的画像逻辑依赖它）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ViewEventMessage implements Serializable {

    /** 浏览者内部 id（=loginId） */
    private Long userId;

    /** 被浏览的帖子内部 id */
    private Long postId;

    /**
     * 帖子表的标签
     */
    private String PostTopic;

    /**
     * 帖子的类型
     */
    private Integer type;

    /**
     * 板块的id
     */
    private Long BoardId;

    /** 停留时长（毫秒），旧事件若未采集则为 null */
    private Long dwellMs;
}
