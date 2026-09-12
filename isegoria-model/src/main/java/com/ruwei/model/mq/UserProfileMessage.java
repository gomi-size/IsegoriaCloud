package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户资料变更消息（替代旧单体本地事件 {@code UserProfileUpdatedEvent}）。
 *
 * <p>routing key {@code es.user.profile}：发布方 user，消费方 rec（按 userId 全量重建该作者的 ES 文档，天然幂等）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileMessage implements Serializable {

    /** 发生资料变更的用户内部 id */
    private Long userId;
}
