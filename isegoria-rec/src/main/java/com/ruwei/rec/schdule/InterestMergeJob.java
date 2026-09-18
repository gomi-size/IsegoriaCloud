package com.ruwei.rec.schdule;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import com.ruwei.model.entity.UserInterest;
import com.ruwei.model.entity.userBehavior;
import com.ruwei.rec.manager.RecCacheManager;
import com.ruwei.rec.service.UserInterestService;
import com.ruwei.rec.service.UserbehaviorService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 长期兴趣合并 Job（每日 03:30，与 {@link EsReconcileTask} 的 03:00 错峰半小时，避免同压 DB）：
 * 取最近 N 天有行为的用户 → 把他们的<b>短期兴趣</b>（Redis {@code uinterest:{uid}:{dim}:{value}}）
 * 按指数衰减合并进<b>长期画像</b> {@code user_interest} → 清理短期键。
 *
 * <p><b>合并公式</b>：{@code weight = weight × 0.9 + 短期增量}；新维度直接以增量起步。
 * upsert 依据唯一键 {@code ukUserDimVal(userId, dimension, value)}——本 Job 单线程串行写入，
 * 无并发冲突；{@code saveOrUpdateBatch} 按 id 判空走 insert / update。</p>
 *
 * <p><b>为什么需要"冷热"两套画像</b>：短期兴趣放 Redis（写多、TTL 短、实时反馈快，但会挥发）；
 * 长期画像落 MySQL（精排读取的稳定底座）。见 {@code RecServiceImpl.loadProfile} 的读侧。</p>
 *
 * <p><b>零跨域</b>：只依赖 {@code UserbehaviorService} / {@code UserInterestService} /
 * {@code RecCacheManager}，三者全在 rec 域内，故本类从旧单体原样迁入（仅改包名与 import）。</p>
 *
 * <p>调度依赖启动类的 {@code @EnableScheduling}（{@code IsegoriaRecApplication} 已有）。</p>
 *
 * @author ruwei
 */
@Component
@Slf4j
public class InterestMergeJob {

    /** 旧权重衰减系数（每日合并衰减 10%） */
    private static final double DECAY = 0.9;
    /**
     * 行为回看窗口（天）：取最近有行为的用户作为合并候选。
     *
     * <p><b>注意</b>：此窗口只决定"哪些用户参与合并"，不是短期兴趣的存活期——
     * 短期键的实际 TTL 是 {@code RecCacheManager.INT_TTL}（当前 2 天）。两者不必相等，
     * 窗口取大一点是为了兜住"TTL 未被续期但仍有残留键"的用户。</p>
     */
    private static final int LOOKBACK_DAYS = 3;

    @Resource
    private UserbehaviorService userbehaviorService;
    @Resource
    private UserInterestService userInterestService;
    @Resource
    private RecCacheManager recCacheManager;

    /**
     * 每日 03:30 执行（{@code EsReconcileTask} 为 03:00，错峰半小时）。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void merge() {
        log.info("========== 长期兴趣合并开始 ==========");
        try {
            doMerge();
        } catch (Exception e) {
            log.error("长期兴趣合并异常", e);
        }
        log.info("========== 长期兴趣合并结束 ==========");
    }

    /**
     * 取候选用户 → 逐个合并。
     */
    private void doMerge() {
        Date since = DateUtil.offsetDay(new Date(), -LOOKBACK_DAYS);
        // 最近 N 天有行为流水（含浏览/点赞/评论/分享/负反馈）的用户即合并候选
        List<Long> userIds = userbehaviorService.lambdaQuery()
                .gt(userBehavior::getCreatedAt, since)
                .select(userBehavior::getUserId)
                .list().stream()
                .map(userBehavior::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            log.info("长期兴趣合并：最近 {} 天无行为用户，跳过", LOOKBACK_DAYS);
            return;
        }
        int merged = 0;
        for (Long uid : userIds) {
            try {
                merged += mergeOne(uid);
            } catch (Exception e) {
                // 单用户失败不影响其余（幂等：短期键未删，明日重跑自然重试）
                log.warn("兴趣合并失败 uid={}: {}", uid, e.getMessage());
            }
        }
        log.info("长期兴趣合并完成：{} 个用户，{} 条画像", userIds.size(), merged);
    }

    /**
     * 单用户合并：短期兴趣 → 长期画像（upsert）→ 删短期键。
     *
     * @param userId 用户内部 id
     * @return 本用户合并的画像条数
     */
    private int mergeOne(Long userId) {
        Map<String, Double> shortTerm = recCacheManager.scanShortInterest(userId);
        if (CollUtil.isEmpty(shortTerm)) {
            return 0;
        }
        // 查现有画像，按 "dim:value" 建索引（一次查全，避免逐条 upsert N 次查询）
        Map<String, UserInterest> existing = userInterestService.lambdaQuery()
                .eq(UserInterest::getUserId, userId)
                .list().stream()
                .collect(Collectors.toMap(
                        i -> i.getDimension() + ":" + i.getValue(), i -> i, (a, b) -> a));

        Date now = new Date();
        List<UserInterest> toSave = new ArrayList<>();

        for (Map.Entry<String, Double> en : shortTerm.entrySet()) {
            String key = en.getKey();   // 形如 "dim:value"
            Double delta = en.getValue();
            int idx = key.indexOf(':');
            if (idx <= 0) {
                // key 污染（缺 ':' 或 dim 为空），跳过
                continue;
            }
            int dim;
            try {
                dim = Integer.parseInt(key.substring(0, idx));
            } catch (NumberFormatException e) {
                continue;
            }
            String value = key.substring(idx + 1);

            UserInterest cur = existing.get(key);
            if (cur == null) {
                // 新维度：以短期增量起步
                cur = new UserInterest();
                cur.setUserId(userId);
                cur.setDimension(dim);
                cur.setValue(value);
                cur.setWeight(BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP));
                cur.setLastActiveAt(now);
            } else {
                // 已有维度：weight = weight×0.9 + 短期增量（指数衰减）
                double oldW = cur.getWeight() == null ? 0d : cur.getWeight().doubleValue();
                cur.setWeight(BigDecimal.valueOf(oldW * DECAY + delta).setScale(4, RoundingMode.HALF_UP));
                cur.setLastActiveAt(now);
            }
            toSave.add(cur);
        }
        // 批量 upsert（单线程 Job 无并发，saveOrUpdateBatch 按 id 判空 insert/update，
        // 唯一键 ukUserDimVal 兜底幂等）+ 删短期键（防残留，TTL 到期本也会清理）
        if (!toSave.isEmpty()) {
            userInterestService.saveOrUpdateBatch(toSave);
        }
        recCacheManager.deleteShortInterest(userId);
        return toSave.size();
    }
}
