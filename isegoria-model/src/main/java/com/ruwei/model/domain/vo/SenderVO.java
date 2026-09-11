package com.ruwei.domain.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 通知发送者（触发者）瘦身展示对象。
 *
 * <p>用于内嵌在 {@code NotificationVO} 中，向通知列表前端提供"谁触发了这条通知"的
 * 必要展示信息。<b>刻意只保留展示所需字段</b>（id / 对外编码 / 昵称 / 头像 / 等级），
 * 不携带 phone / email / admin / 各类计数等敏感或冗余数据，避免通知这类偏公开界面泄露隐私。</p>
 */
@Data
public class SenderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 发送者内部 id（= Sa-Token loginId，与 notification.senderId 对应）
     */
    private Long id;

    /**
     * 发送者对外展示编码（前端用于拼接主页链接，如 /user/{userId}）
     */
    private Long userId;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像 URL
     */
    private String avatar;

    /**
     * 等级（可选，用于前端做徽章展示）
     */
    private Integer level;
}
