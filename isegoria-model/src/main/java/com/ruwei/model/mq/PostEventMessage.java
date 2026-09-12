package com.ruwei.model.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 发帖事件消息（替代旧单体本地事件 {@code PostEvent}）。
 *
 * <p>routing key {@code evt.post}：发布方 post（审核通过后），投递 notify（新帖通知粉丝）。</p>
 *
 * <p><b>设计要点</b>：粉丝列表由发布方查好放进消息（原 {@code PostServiceImpl:766} 直查 user_follow），
 * 消费方零查询即可批量发通知——避免消费端为查粉丝再发起一次跨服务调用。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostEventMessage implements Serializable {

    /** 帖子作者内部 id */
    private Long postUserId;

    /** 帖子内部 id */
    private Long postId;

    /** 作者粉丝内部 id 列表（发布方已查好） */
    private List<Long> fans;
}
