package com.ruwei.interaction.mq;

import com.ruwei.model.dto.LikePersistMessage;
import lombok.Getter;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * 点赞消息的 CorrelationData 扩展：把原始 {@link LikePersistMessage} 一并带上，
 * 供 publisher-confirm 失败回调（{@code ack=false}）取回业务对象做同步降级直写。
 *
 * <p>不用 {@code CorrelationData.getReturned()}：它只返回 Spring AMQP 的 {@code ReturnedMessage}
 * 包装，还原不了原始业务对象。</p>
 *
 * <p>放在 interaction 而非 isegoria-model：本类继承 Spring AMQP 的 {@code CorrelationData}，
 * 而 model 是纯 POJO 模块（无 amqp 依赖）。</p>
 *
 * @author ruwei
 */
@Getter
public class LikeCorrelationData extends CorrelationData {

    /** 发送前携带的原始落库消息 */
    private final LikePersistMessage message;

    public LikeCorrelationData(String id, LikePersistMessage message) {
        super(id);
        this.message = message;
    }
}