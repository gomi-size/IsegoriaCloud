package com.ruwei.social.config;

import com.ruwei.common.mq.ForumMqConstants;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * forum 事件总线拓扑（social 服务版）：只声明交换机与 JSON 转换器。
 *
 * <p>social 是纯发布方（发 {@code evt.follow} / {@code evt.boardfollow}，不消费 forum 队列），
 * 队列与绑定由消费方 notify 声明。</p>
 *
 * <p>全 JVM 唯一 {@link Jackson2JsonMessageConverter} Bean，勿在别处重复声明。</p>
 *
 * @author ruwei
 */
@Configuration
public class ForumMqConfig {

    @Bean
    public DirectExchange forumDlx() {
        return ExchangeBuilder.directExchange(ForumMqConstants.DLX).durable(true).build();
    }

    @Bean
    public TopicExchange forumExchange() {
        return ExchangeBuilder.topicExchange(ForumMqConstants.EXCHANGE).durable(true).build();
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}