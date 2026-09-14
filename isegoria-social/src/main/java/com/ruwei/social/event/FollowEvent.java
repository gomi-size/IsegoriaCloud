package com.ruwei.social.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;


/**
 *发送消息的载体
 */
@Getter
public class FollowEvent extends ApplicationEvent {

    public static final int ACTION_FOLLOW = 1;
    public static final int ACTION_CANCEL = 2;

    /** 触发者（主动关注方）内部 id */
    private final Long actorId;
    /** 被关注者（接收通知方）内部 id */
    private final Long followeeId;
    /**
     * 1是关注2是取关
     */
    private final int action;

    public FollowEvent(Object source, Long actorId, Long followeeId ,int action) {
        super(source);
        this.actorId = actorId;
        this.followeeId = followeeId;
        this.action=action;
    }
}