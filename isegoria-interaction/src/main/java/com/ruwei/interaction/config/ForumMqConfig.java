package com.ruwei.interaction.config;

import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.interaction.mq.LikeMqConfig;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * forum 事件总线拓扑（interaction 服务版）：交换机声明 + JSON 消息转换器。
 *
 * <p>interaction 是<b>纯发布方</b>（发 {@code evt.like} 与 {@code es.post.index}，不消费任何 forum 队列），
 * 因此只声明交换机与转换器，不声明队列与绑定 —— 队列由 notify（evt.like→站内通知）/
 * rec（evt.like→兴趣画像、es.post.index→ES 重建）声明。</p>
 *
 * <p>注意：{@link Jackson2JsonMessageConverter} 是全 JVM 唯一 Bean，
 * {@link LikeMqConfig} 依赖它完成与 RabbitTemplate 的自动装配，勿在别处重复声明。</p>
 *
 * @author ruwei
 */
@Configuration
public class ForumMqConfig {

    /** 死信交换机（direct，durable）：forum.* 队列的 DLQ 归宿 */
    @Bean
    public DirectExchange forumDlx() {
        return ExchangeBuilder.directExchange(ForumMqConstants.DLX).durable(true).build();
    }

    /** 主事件交换机（topic，durable） */
    @Bean
    public TopicExchange forumExchange() {
        return ExchangeBuilder.topicExchange(ForumMqConstants.EXCHANGE).durable(true).build();
    }

    /** JSON 消息转换器（消息体为 model.mq POJO） */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}