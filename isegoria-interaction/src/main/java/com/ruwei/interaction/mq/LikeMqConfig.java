package com.ruwei.interaction.mq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 点赞模块 MQ 拓扑（存量，不变）：
 * <pre>
 * like.exchange (direct, durable)
 *   ├─ rk like.post     → like.post.queue     (durable, dlq=like.post.dlq)
 *   └─ rk like.comment  → like.comment.queue  (durable, dlq=like.comment.dlq)
 * like.dlx (direct, durable) 承载死信转发
 * </pre>
 * 消费者手动 ACK；本地重试 3 次耗尽后 nack(requeue=false) → 进死信队列，告警人工处理。
 * interaction 既是发布方（LikeServiceImpl）也是唯一消费方（LikePersistConsumer），
 * 因此队列 / 绑定在本服务声明。
 *
 * @author ruwei
 */
@Configuration
public class LikeMqConfig {

    /** 点赞业务交换机（direct，durable） */
    public static final String EXCHANGE = "like.exchange";
    /** 点赞死信交换机（direct，durable） */
    public static final String DLX = "like.dlx";

    /** 帖子点赞落库队列 */
    public static final String POST_QUEUE = "like.post.queue";
    /** 帖子点赞死信队列 */
    public static final String POST_DLQ = "like.post.dlq";
    /** 帖子点赞 routing key */
    public static final String POST_RK = "like.post";

    /** 评论点赞落库队列 */
    public static final String COMMENT_QUEUE = "like.comment.queue";
    /** 评论点赞死信队列 */
    public static final String COMMENT_DLQ = "like.comment.dlq";
    /** 评论点赞 routing key */
    public static final String COMMENT_RK = "like.comment";

    /** 业务交换机 */
    @Bean
    public DirectExchange likeExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    /** 死信交换机 */
    @Bean
    public DirectExchange likeDlx() {
        return ExchangeBuilder.directExchange(DLX).durable(true).build();
    }

    /** 构建带死信规则的业务队列 */
    private Queue buildQueueWithDlq(String queue, String dlq) {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    @Bean
    public Queue likePostQueue() {
        return buildQueueWithDlq(POST_QUEUE, POST_DLQ);
    }

    @Bean
    public Queue likeCommentQueue() {
        return buildQueueWithDlq(COMMENT_QUEUE, COMMENT_DLQ);
    }

    @Bean
    public Queue likePostDlq() {
        return QueueBuilder.durable(POST_DLQ).build();
    }

    @Bean
    public Queue likeCommentDlq() {
        return QueueBuilder.durable(COMMENT_DLQ).build();
    }

    @Bean
    public Binding likePostBinding() {
        return BindingBuilder.bind(likePostQueue()).to(likeExchange()).with(POST_RK);
    }

    @Bean
    public Binding likeCommentBinding() {
        return BindingBuilder.bind(likeCommentQueue()).to(likeExchange()).with(COMMENT_RK);
    }

    @Bean
    public Binding likePostDlqBinding() {
        return BindingBuilder.bind(likePostDlq()).to(likeDlx()).with(POST_DLQ);
    }

    @Bean
    public Binding likeCommentDlqBinding() {
        return BindingBuilder.bind(likeCommentDlq()).to(likeDlx()).with(COMMENT_DLQ);
    }
}