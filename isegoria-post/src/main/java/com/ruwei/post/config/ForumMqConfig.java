package com.ruwei.post.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ruwei.common.mq.ForumMqConstants;

/**
 * forum 事件总线拓扑：交换机声明 + JSON 消息转换器（post 服务版）。
 *
 * <p>post 是<b>纯发布方</b>（发 evt.post / evt.admin / evt.view / evt.share / es.post.index，
 * 不消费任何 forum 队列），因此只声明交换机与转换器，<b>不声明队列与绑定</b>。
 * 声明幂等（durable 已存在不重复建），多服务重复声明无害；
 * 发布方主动声明可避免"先发布、后由消费方建拓扑"期间消息静默丢失。</p>
 *
 * <p>{@code Jackson2JsonMessageConverter} Bean 存在时，Spring AMQP 的 {@code RabbitTemplate}
 * 会自动采用它 —— 消息体为 {@code isegoria-model} 的 {@code model.mq} POJO。</p>
 *
 * @author ruwei
 */
@Configuration
public class ForumMqConfig {

    /**
     * 死信交换机（direct，durable）：各消费方队列的 DLQ 归宿，post 虽不消费也一并声明保证拓扑完整。
     */
    @Bean
    public DirectExchange forumDlx() {
        return ExchangeBuilder.directExchange(ForumMqConstants.DLX).durable(true).build();
    }

    /**
     * 主事件交换机（topic，durable）：本服务全部事件与 ES 索引消息的发布入口。
     */
    @Bean
    public TopicExchange forumExchange() {
        return ExchangeBuilder.topicExchange(ForumMqConstants.EXCHANGE).durable(true).build();
    }

    /**
     * JSON 消息转换器（消息体为 model.mq POJO）。
     */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
