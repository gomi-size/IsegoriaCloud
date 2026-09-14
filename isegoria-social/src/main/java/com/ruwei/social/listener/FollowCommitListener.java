package com.ruwei.social.listener;

import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.model.mq.BoardFollowEventMessage;
import com.ruwei.model.mq.FollowEventMessage;
import com.ruwei.social.event.BoardFollowEvent;
import com.ruwei.social.event.FollowEvent;
import com.ruwei.social.manager.FollowCacheManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 关注关系「事务提交后」的统一副作用监听器（原 {@code FollowRedisEventListener} 改造）。
 *
 * <p>为什么必须有这一层（而不是在 Service 里直接做）：</p>
 * <ul>
 *   <li><b>缓存</b>：Redis 热索引若在事务内更新，事务回滚后索引与 DB 长期不一致，
 *       只能等 7 天 TTL 过期才自愈 —— 期间「是否已关注」全是错的；</li>
 *   <li><b>MQ</b>：事务内发消息，回滚时会发出「假关注」事件，通知模块据此发错通知。</li>
 * </ul>
 * <p>因此 Service 只发本地 {@link FollowEvent}，本类在 {@code AFTER_COMMIT} 后统一处理两件事。</p>
 *
 * <p><b>已知取舍</b>：MQ 发送失败只记日志、不重试（旧单体本地事件失败同样只记日志）。
 * 需要强可靠时二期引入本地消息表 / 定时补偿。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class FollowCommitListener {

    /** 关注关系 Redis 热索引（uf:following / uf:followers） */
    @Resource
    private FollowCacheManager cache;

    /** 发 evt.follow（forum.exchange）通知 notify 服务 */
    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 事务提交后：刷 Redis 热索引 + 投递 {@code evt.follow}。
     *
     * <p>{@code @Async("eventTaskExecutor")} 走 common 的通用线程池（队列满时 CallerRunsPolicy），
     * 不阻塞业务线程、也不影响事务已提交的事实。</p>
     *
     * @param event 本地关注事件（actorId / followeeId / action）
     */
    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFollowCommitted(FollowEvent event) {
        // ① 刷新 Redis 热索引：失败不影响 DB（已提交），键过期后读路径回源重建自愈
        try {
            if (event.getAction() == FollowEvent.ACTION_FOLLOW) {
                cache.addFollowing(event.getActorId(), event.getFolloweeId());
            } else {
                cache.removeFollow(event.getActorId(), event.getFolloweeId());
            }
        } catch (Exception e) {
            log.error("同步关注关系到 Redis 失败: actor={}, followee={}, action={}",
                    event.getActorId(), event.getFolloweeId(), event.getAction(), e);
        }

        // ② 投递 MQ（notify 站内通知）；取关也发，由消费方决定是否落通知
        try {
            String action = event.getAction() == FollowEvent.ACTION_FOLLOW
                    ? FollowEventMessage.ACTION_FOLLOW
                    : FollowEventMessage.ACTION_CANCEL;
            rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                    ForumMqConstants.RK_EVT_FOLLOW,
                    new FollowEventMessage(event.getActorId(), event.getFolloweeId(), action));
        } catch (Exception e) {
            log.error("关注事件投递 MQ 失败: actor={}, followee={}, action={}",
                    event.getActorId(), event.getFolloweeId(), event.getAction(), e);
        }
    }

    /**
     * 事务提交后：投递板块关注 {@code evt.boardfollow}（通知吧主）。
     *
     * <p>板块关注<b>没有 Redis 热索引</b>，所以这里只做 MQ 投递（与
     * {@link #onFollowCommitted(FollowEvent)} 的差异）；但"提交后才产生副作用"这一点
     * 与用户关注保持一致 —— 事务回滚不会发出假关注。</p>
     *
     * <p>只有"关注"会发事件（取关不发通知），故本方法无 action 分支。</p>
     *
     * @param event 本地板块关注事件（loginId / boardId / boardCreatorId）
     */
    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoardFollowCommitted(BoardFollowEvent event) {
        try {
            rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                    ForumMqConstants.RK_EVT_BOARD_FOLLOW,
                    new BoardFollowEventMessage(event.getLoginId(), event.getBoardId(),
                            event.getBoardCreatorId()));
        } catch (Exception e) {
            log.error("板块关注事件投递 MQ 失败: loginId={}, boardId={}, creatorId={}",
                    event.getLoginId(), event.getBoardId(), event.getBoardCreatorId(), e);
        }
    }
}