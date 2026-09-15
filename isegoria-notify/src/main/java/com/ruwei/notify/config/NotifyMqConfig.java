package com.ruwei.notify.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ruwei.common.mq.ForumMqConstants;

/**
 * forum 事件总线拓扑（notify 服务版）：交换机 + <b>8 个业务队列 + 8 个死信队列 + 8 条绑定</b>。
 *
 * <p><b>为什么 notify 要声明这么多东西</b>：MQ 的规矩是「谁消费，谁声明队列与绑定」。
 * notify 是这 8 条 routing key 的唯一消费方，所以全项目的这 8 个队列都在这里创建。
 * 发布方（post / interaction / social）只声明交换机，不碰队列。</p>
 *
 * <p><b>死信（DLQ）机制</b>：每个业务队列都通过 {@code x-dead-letter-exchange / x-dead-letter-routing-key}
 * 挂到 {@code forum.dlx}。消费者处理失败调用 {@code basicNack(requeue=false)} 时，消息不会丢，
 * 而是被投进对应的 DLQ（如 {@code forum.notify.like.queue.dlq}），等人排查后手动重放。</p>
 *
 * <p><b>声明是幂等的</b>：durable 的交换机/队列已存在时重复声明不会报错。消费方主动声明可避免
 * 「消息先到、队列还没建」期间的静默丢失。</p>
 *
 * @author ruwei
 */
@Configuration
public class NotifyMqConfig {

    /** 业务队列 → 死信队列 的后缀约定 */
    private static final String DLQ_SUFFIX = ".dlq";

    /** 主事件交换机（topic，durable）：8 条路由键都从它进来 */
    @Bean
    public TopicExchange forumExchange() {
        return ExchangeBuilder.topicExchange(ForumMqConstants.EXCHANGE).durable(true).build();
    }

    /** 死信交换机（direct，durable）：承载全部 forum.* 队列的死信 */
    @Bean
    public DirectExchange forumDlx() {
        return ExchangeBuilder.directExchange(ForumMqConstants.DLX).durable(true).build();
    }

    /** JSON 消息转换器：消息体是 isegoria-model 的 model.mq POJO，必须与发布方一致 */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ==================== 业务队列（8 个，各挂自己的 DLQ） ====================

    /**
     * 构造带死信规则的业务队列。
     *
     * @param queue 队列名
     * @return durable 队列，失败消息转发到同名 + ".dlq"
     */
    private Queue notifyQueue(String queue) {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", ForumMqConstants.DLX)
                .withArgument("x-dead-letter-routing-key", queue + DLQ_SUFFIX)
                .build();
    }

    /** 构造死信队列（普通 durable 队列，不再级联死信，避免死循环） */
    private Queue dlq(String queue) {
        return QueueBuilder.durable(queue + DLQ_SUFFIX).build();
    }

    /** 业务队列绑定到 topic 交换机，routing key 与发布方完全一致 */
    private Binding bindNotify(Queue queue, String routingKey) {
        return BindingBuilder.bind(queue).to(forumExchange()).with(routingKey);
    }

    /** DLQ 绑定到死信交换机（direct，routing key = DLQ 名） */
    private Binding bindDlq(Queue dlq) {
        return BindingBuilder.bind(dlq).to(forumDlx()).with(dlq.getName());
    }

    // ---- 点赞：rk evt.like ----
    @Bean
    public Queue notifyLikeQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_LIKE); }

    @Bean
    public Queue notifyLikeDlq() { return dlq(ForumMqConstants.Q_NOTIFY_LIKE); }

    @Bean
    public Binding notifyLikeBind() {
        return bindNotify(notifyLikeQueue(), ForumMqConstants.RK_EVT_LIKE);
    }

    @Bean
    public Binding notifyLikeDlqBind() { return bindDlq(notifyLikeDlq()); }

    // ---- 评论：rk evt.comment ----
    @Bean
    public Queue notifyCommentQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_COMMENT); }

    @Bean
    public Queue notifyCommentDlq() { return dlq(ForumMqConstants.Q_NOTIFY_COMMENT); }

    @Bean
    public Binding notifyCommentBind() {
        return bindNotify(notifyCommentQueue(), ForumMqConstants.RK_EVT_COMMENT);
    }

    @Bean
    public Binding notifyCommentDlqBind() { return bindDlq(notifyCommentDlq()); }

    // ---- 回复：rk evt.reply ----
    @Bean
    public Queue notifyReplyQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_REPLY); }

    @Bean
    public Queue notifyReplyDlq() { return dlq(ForumMqConstants.Q_NOTIFY_REPLY); }

    @Bean
    public Binding notifyReplyBind() {
        return bindNotify(notifyReplyQueue(), ForumMqConstants.RK_EVT_REPLY);
    }

    @Bean
    public Binding notifyReplyDlqBind() { return bindDlq(notifyReplyDlq()); }

    // ---- 分享：rk evt.share ----
    @Bean
    public Queue notifyShareQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_SHARE); }

    @Bean
    public Queue notifyShareDlq() { return dlq(ForumMqConstants.Q_NOTIFY_SHARE); }

    @Bean
    public Binding notifyShareBind() {
        return bindNotify(notifyShareQueue(), ForumMqConstants.RK_EVT_SHARE);
    }

    @Bean
    public Binding notifyShareDlqBind() { return bindDlq(notifyShareDlq()); }

    // ---- 关注用户：rk evt.follow ----
    @Bean
    public Queue notifyFollowQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_FOLLOW); }

    @Bean
    public Queue notifyFollowDlq() { return dlq(ForumMqConstants.Q_NOTIFY_FOLLOW); }

    @Bean
    public Binding notifyFollowBind() {
        return bindNotify(notifyFollowQueue(), ForumMqConstants.RK_EVT_FOLLOW);
    }

    @Bean
    public Binding notifyFollowDlqBind() { return bindDlq(notifyFollowDlq()); }

    // ---- 关注板块：rk evt.boardfollow ----
    @Bean
    public Queue notifyBoardFollowQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_BOARD_FOLLOW); }

    @Bean
    public Queue notifyBoardFollowDlq() { return dlq(ForumMqConstants.Q_NOTIFY_BOARD_FOLLOW); }

    @Bean
    public Binding notifyBoardFollowBind() {
        return bindNotify(notifyBoardFollowQueue(), ForumMqConstants.RK_EVT_BOARD_FOLLOW);
    }

    @Bean
    public Binding notifyBoardFollowDlqBind() { return bindDlq(notifyBoardFollowDlq()); }

    // ---- 发布新帖（通知粉丝）：rk evt.post ----
    @Bean
    public Queue notifyPostQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_POST); }

    @Bean
    public Queue notifyPostDlq() { return dlq(ForumMqConstants.Q_NOTIFY_POST); }

    @Bean
    public Binding notifyPostBind() {
        return bindNotify(notifyPostQueue(), ForumMqConstants.RK_EVT_POST);
    }

    @Bean
    public Binding notifyPostDlqBind() { return bindDlq(notifyPostDlq()); }

    // ---- 管理员审核结果：rk evt.admin ----
    @Bean
    public Queue notifyAdminQueue() { return notifyQueue(ForumMqConstants.Q_NOTIFY_ADMIN); }

    @Bean
    public Queue notifyAdminDlq() { return dlq(ForumMqConstants.Q_NOTIFY_ADMIN); }

    @Bean
    public Binding notifyAdminBind() {
        return bindNotify(notifyAdminQueue(), ForumMqConstants.RK_EVT_ADMIN);
    }

    @Bean
    public Binding notifyAdminDlqBind() { return bindDlq(notifyAdminDlq()); }
}