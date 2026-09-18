package com.ruwei.rec.empty.Contants;

/**
 * rec 服务用到的 Redis key 常量（集中一处，便于排查与避免魔法字符串）。
 *
 * <p>本类替代旧单体里"常量挂在监听器类上"的做法（旧代码
 * {@code EsReconcileTask} 直接引用 {@code PostIndexEventListener.ES_SYNC_FAIL_KEY}）——
 * 消费者和对账任务现在都可以引用这里，职责更清晰。</p>
 *
 * @author ruwei
 */
public final class EsSyncConstants {

    private EsSyncConstants() {
    }

    /** ES 同步失败重试集合：{@code es:sync:fail:ids}，成员为 postId，由每日对账任务逐帖重试 */
    public static final String ES_SYNC_FAIL_KEY = "es:sync:fail:ids";
}