package com.ruwei.notify.publisher;

import com.ruwei.model.dto.NotifyPushMessage;

/**
 * 通知实时推送抽象：把通知推给指定内部 id 的用户。
 *
 * <p>抽成接口是为了留替换空间（例如二期接 STOMP relay / 钉钉机器人 时只换实现）。</p>
 *
 * @author ruwei
 */
public interface NotificationPublisher {

    /**
     * 推送一条通知给内部 id 对应的用户。
     *
     * <p>失败静默（用户离线 / WS 未连接）：历史以 {@code notification} 表为准，
     * 前端下次登录会主动拉取，推送失败不算业务失败。</p>
     *
     * @param internalId 接收者内部 id
     * @param message    推送内容
     */
    void push(Long internalId, NotifyPushMessage message);
}