package com.ruwei.social.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 板块关注变更的<b>本地事件</b>（同 JVM，不跨服务）。
 *
 * <p>与 {@link FollowEvent} 的区别：板块关注<b>没有 Redis 热索引</b>，所以本事件不承载"刷缓存"，
 * 只作为「事务已提交」的信号，由 {@code FollowCommitListener#onBoardFollowCommitted}
 * 在 {@code AFTER_COMMIT} 阶段投递 MQ {@code evt.boardfollow}。</p>
 *
 * <p>为什么不在 Service 里直接发 MQ：事务回滚时会发出「假关注」，吧主收到不存在的关注通知。</p>
 *
 * <p>跨服务通知请用 {@code com.ruwei.model.mq.BoardFollowEventMessage}，勿混用。</p>
 *
 * @author ruwei
 */
@Getter
public class BoardFollowEvent extends ApplicationEvent {

    /** 关注者（发起关注的人）内部 id */
    private final Long loginId;

    /** 板块内部 id */
    private final Long boardId;

    /** 板块创建者内部 id（通知接收者 / 吧主） */
    private final Long boardCreatorId;

    public BoardFollowEvent(Object source, Long loginId, Long boardId, Long boardCreatorId) {
        super(source);
        this.loginId = loginId;
        this.boardId = boardId;
        this.boardCreatorId = boardCreatorId;
    }
}