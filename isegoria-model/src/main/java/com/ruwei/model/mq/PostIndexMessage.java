package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 帖子 ES 索引同步消息（替代旧单体本地事件 {@code PostIndexEvent}）。
 *
 * <p>routing key {@code es.post.index}：发布方 post（上下架 / 编辑下架 / 逻辑删除）与
 * interaction（点赞落库成功后同步计数），消费方 rec（{@code EsPostSyncService}）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostIndexMessage implements Serializable {

    /** 索引动作：重建 / 写入 */
    public static final String ACTION_INDEX = "INDEX";

    /** 索引动作：删除文档 */
    public static final String ACTION_DELETE = "DELETE";

    /** 帖子内部 id */
    private Long postId;

    /** 动作：{@link #ACTION_INDEX} / {@link #ACTION_DELETE}（原枚举改为字符串，避免跨服务枚举耦合） */
    private String action;
}
