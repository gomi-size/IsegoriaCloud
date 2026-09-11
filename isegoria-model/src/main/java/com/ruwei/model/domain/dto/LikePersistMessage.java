package com.ruwei.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 点赞异步落库消息（仅用于 like.exchange）。
 * eventId 作为 MQ 消费幂等键（SETNX like:mq:dedup:{eventId}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LikePersistMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private String eventId;       // UUID
    private Integer targetType;   // 1帖子 2评论
    private Long targetId;        // postId / commentId（内部 id）
    private Long userId;          // 点赞者内部 id
    private Integer action;       // 1点赞 0取消
    private Long timestamp;       // 毫秒
}