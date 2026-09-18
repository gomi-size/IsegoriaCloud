package com.ruwei.rec.config;

import com.ruwei.common.mq.ForumMqConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * forum 事件总线拓扑（rec 服务版）：交换机 + 3 个业务队列 + 3 个死信队列 + 6 条绑定。
 *
 * <p><b>注意兴趣队列的特殊性</b>：{@code forum.rec.interest.queue} <b>同时绑定 4 个 routing key</b>
 * （evt.like / evt.comment / evt.share / evt.view）—— 这样发布方只发一次，点赞/评论/分享/浏览
 * 四种信号都进同一个队列，由 {@code RecInterestConsumer} 按消息类型分发。
 * 好处是兴趣画像的写入路径收敛在一条队列上；代价是消费端要处理多类型（见消费者注释）。</p>
 *
 * @author ruwei
 */
@Configuration
public class RecMqConfig {

    private static final String DLQ_SUFFIX=".dlq";
    /**
     * 主事件交换机
     */
    @Bean
    public TopicExchange forumExchange(){
        return ExchangeBuilder.topicExchange(ForumMqConstants.EXCHANGE).durable(true).build();
    }

    /**
     * 死信交换机
     */
    @Bean
    public DirectExchange forumDlx(){
        return ExchangeBuilder.directExchange(ForumMqConstants.DLX).durable(true).build();
    }

    /** JSON 消息转换器（消息体是 model.mq 的 POJO，必须与发布方一致） */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    /** 带死信规则的业务队列 */
    private Queue recQueue(String queue) {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", ForumMqConstants.DLX)
                .withArgument("x-dead-letter-routing-key", queue + DLQ_SUFFIX)
                .build();
    }

    /** 死信队列（不再级联死信，避免死循环） */
    private Queue dlq(String queue) {
        return QueueBuilder.durable(queue + DLQ_SUFFIX).build();
    }


    // ==================== 兴趣队列：4 条 rk 共用 ====================

    /**
     * 声明队列
     * @return
     */
    @Bean
    public Queue recInterestQueue() { return recQueue(ForumMqConstants.Q_REC_INTEREST); }

    @Bean
    public Queue recInterestDlq() { return dlq(ForumMqConstants.Q_REC_INTEREST); }

    @Bean
    public Binding recInterestBindLike(Queue recInterestQueue) {
        return BindingBuilder.bind(recInterestQueue).to(forumExchange()).with(ForumMqConstants.RK_EVT_LIKE);
    }

    /**
     *队列绑定路由
     * @param recInterestQueue
     * @return
     */
    @Bean
    public Binding recInterestBindComment(Queue recInterestQueue) {
        return BindingBuilder.bind(recInterestQueue).to(forumExchange()).with(ForumMqConstants.RK_EVT_COMMENT);
    }

    @Bean
    public Binding recInterestBindShare(Queue recInterestQueue) {
        return BindingBuilder.bind(recInterestQueue).to(forumExchange()).with(ForumMqConstants.RK_EVT_SHARE);
    }

    @Bean
    public Binding recInterestBindView(Queue recInterestQueue) {
        return BindingBuilder.bind(recInterestQueue).to(forumExchange()).with(ForumMqConstants.RK_EVT_VIEW);
    }

    @Bean
    public Binding recInterestDlqBind(Queue recInterestDlq) {
        return BindingBuilder.bind(recInterestDlq).to(forumDlx()).with(recInterestDlq.getName());
    }

    // ==================== ES 索引队列：rk es.post.index ====================

    @Bean
    public Queue esIndexQueue() { return recQueue(ForumMqConstants.Q_ES_INDEX); }

    @Bean
    public Queue esIndexDlq() { return dlq(ForumMqConstants.Q_ES_INDEX); }

    @Bean
    public Binding esIndexBind(Queue esIndexQueue) {
        return BindingBuilder.bind(esIndexQueue).to(forumExchange()).with(ForumMqConstants.RK_ES_POST_INDEX);
    }

    @Bean
    public Binding esIndexDlqBind(Queue esIndexDlq) {
        return BindingBuilder.bind(esIndexDlq).to(forumDlx()).with(esIndexDlq.getName());
    }

    // ==================== 用户资料队列：rk es.user.profile ====================

    @Bean
    public Queue esProfileQueue() { return recQueue(ForumMqConstants.Q_ES_PROFILE); }

    @Bean
    public Queue esProfileDlq() { return dlq(ForumMqConstants.Q_ES_PROFILE); }

    @Bean
    public Binding esProfileBind(Queue esProfileQueue) {
        return BindingBuilder.bind(esProfileQueue).to(forumExchange()).with(ForumMqConstants.RK_ES_USER_PROFILE);
    }

    @Bean
    public Binding esProfileDlqBind(Queue esProfileDlq) {
        return BindingBuilder.bind(esProfileDlq).to(forumDlx()).with(esProfileDlq.getName());
    }
}