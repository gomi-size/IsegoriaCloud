package com.ruwei.common.mq;

/**
 * forum 事件总线拓扑常量（发布方/消费方共用，避免魔法字符串）。
 * 交换机：forum.exchange (topic, durable)；死信交换机：forum.dlx (direct, durable)。
 */
public final class ForumMqConstants {
    private ForumMqConstants() {}

    public static final String EXCHANGE = "forum.exchange";
    public static final String DLX = "forum.dlx";

    // ---- routing keys ----
    public static final String RK_EVT_LIKE = "evt.like";
    public static final String RK_EVT_COMMENT = "evt.comment";
    public static final String RK_EVT_REPLY = "evt.reply";
    public static final String RK_EVT_SHARE = "evt.share";
    public static final String RK_EVT_VIEW = "evt.view";
    public static final String RK_EVT_FOLLOW = "evt.follow";
    public static final String RK_EVT_BOARD_FOLLOW = "evt.boardfollow";
    public static final String RK_EVT_POST = "evt.post";
    public static final String RK_EVT_ADMIN = "evt.admin";
    public static final String RK_ES_POST_INDEX = "es.post.index";
    public static final String RK_ES_USER_PROFILE = "es.user.profile";

    // ---- queues（消费方声明，全部挂 DLQ） ----
    public static final String Q_NOTIFY_LIKE = "forum.notify.like.queue";
    public static final String Q_NOTIFY_COMMENT = "forum.notify.comment.queue";
    public static final String Q_NOTIFY_REPLY = "forum.notify.reply.queue";
    public static final String Q_NOTIFY_SHARE = "forum.notify.share.queue";
    public static final String Q_NOTIFY_FOLLOW = "forum.notify.follow.queue";
    public static final String Q_NOTIFY_BOARD_FOLLOW = "forum.notify.boardfollow.queue";
    public static final String Q_NOTIFY_POST = "forum.notify.post.queue";
    public static final String Q_NOTIFY_ADMIN = "forum.notify.admin.queue";
    public static final String Q_REC_INTEREST = "forum.rec.interest.queue";
    public static final String Q_ES_INDEX = "forum.es.index.queue";
    public static final String Q_ES_PROFILE = "forum.es.profile.queue";

}