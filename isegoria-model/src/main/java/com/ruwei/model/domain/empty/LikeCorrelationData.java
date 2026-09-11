package com.ruwei.domain.empty;

import com.ruwei.domain.dto.LikePersistMessage;
import lombok.Getter;
import org.springframework.amqp.rabbit.connection.CorrelationData;

@Getter
public class LikeCorrelationData extends CorrelationData {
    // 把你的原始消息对象作为属性存起来
    private LikePersistMessage message;

    public LikeCorrelationData(String id, LikePersistMessage message) {
        super(id);
        this.message = message;
    }

}