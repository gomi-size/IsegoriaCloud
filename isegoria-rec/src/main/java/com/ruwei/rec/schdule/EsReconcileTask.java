package com.ruwei.rec.schdule;

import com.ruwei.innerservice.InnerPostService;
import com.ruwei.model.entity.Post;
import com.ruwei.rec.consumer.PostIndexConsumer;
import com.ruwei.rec.empty.Contants.EsSyncConstants;
import com.ruwei.rec.empty.PostDoc;
import com.ruwei.rec.service.EsPostSyncService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ES 索引一致性定时对账（每日凌晨 3:00）：
 *
 * <ol>
 *   <li><b>失败重试</b>：重放 Redis 中 {@link EsSyncConstants#ES_SYNC_FAIL_KEY}
 *       （{@code es:sync:fail:ids}）记录的同步失败帖子 id
 *       （由 {@link PostIndexConsumer} 写入），成功即移出队列；</li>
 *   <li><b>全量对账</b>：对比「MySQL 应索引的帖子 id 集合」与「ES 中已存在的 id 集合」，
 *       缺失的补索引（少补），多余的删索引（多删），保证最终一致。</li>
 * </ol>
 *
 * <p><b>Phase 8 改造</b>：① Redis key 常量从旧 {@code PostIndexEventListener.ES_SYNC_FAIL_KEY}
 * 迁到 {@link EsSyncConstants}（旧写法跨类引用静态常量，类一改名就断）；② MySQL 侧扫全表由
 * MP 页码分页改为<b>游标分页</b>（{@link InnerPostService#listPostIdsAfterId}）。
 * 其余（Redis 集合对比、{@code esPostSyncService.indexByPostId}）一字未改。</p>
 *
 * <p>{@code indexByPostId} 内部会"满足条件 → 索引 / 不满足 → 删除"，所以对账的"少补多删"
 * 复用它<b>是安全的</b>（旧实现的设计意图）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class EsReconcileTask {

    @Resource
    private EsPostSyncService esPostSyncService;
    @Resource
    private ElasticsearchOperations operations;
    @DubboReference
    private InnerPostService innerPostService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 每批处理量 */
    private static final int BATCH_SIZE = 500;

    /**
     * 每日凌晨 3:00 执行。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcile() {
        log.info("========== ES 定时对账开始 ==========");
        try {
            retryFailedSync();
            doReconcile();
        } catch (Exception e) {
            log.error("ES 定时对账异常", e);
        }
        log.info("========== ES 定时对账结束 ==========");
    }

    /**
     * 1. 重试 Redis 失败队列：indexByPostId 内部会按当前状态判断
     * （满足 shouldIndex → 索引；不满足 → 删除），语义安全。
     */
    private void retryFailedSync() {
        Set<String> failedIds = stringRedisTemplate.opsForSet()
                .members(EsSyncConstants.ES_SYNC_FAIL_KEY);
        if (failedIds == null || failedIds.isEmpty()) {
            return;
        }
        for (String idStr : failedIds) {
            try {
                esPostSyncService.indexByPostId(Long.valueOf(idStr));
                stringRedisTemplate.opsForSet().remove(EsSyncConstants.ES_SYNC_FAIL_KEY, idStr);
                log.info("ES 失败重试成功 postId={}", idStr);
            } catch (Exception e) {
                log.warn("ES 失败重试仍失败 postId={}，保留待下轮重试", idStr);
            }
        }
    }

    /**
     * 2. 全量对账：MySQL 应索引集合 vs ES 现有集合，少补多删。
     */
    private void doReconcile() {
        // 2.1 MySQL 侧：游标分批扫全部帖子（升序），过滤 shouldIndex，得到应索引 id 集合
        //     游标而非页码：深分页在 MySQL 上越来越慢；扫表期间新增的帖子也不会被漏掉
        Set<Long> mysqlIds = new HashSet<>();
        Long lastId = 0L;
        while (true) {
            List<Long> ids = innerPostService.listPostIdsAfterId(lastId, BATCH_SIZE);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            List<Post> posts = innerPostService.listByIds(ids);
            if (posts != null) {
                posts.stream()
                        .filter(esPostSyncService::shouldIndex)
                        .map(Post::getId)
                        .forEach(mysqlIds::add);
            }
            lastId = ids.get(ids.size() - 1);
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }

        // 2.2 ES 侧：分页扫出全部文档 id
        // 注意：from+size 深分页上限默认 1w，一期数据量足够；超量需改 search_after
        Set<Long> esIds = new HashSet<>();
        int pageNum = 0;
        while (true) {
            NativeQuery q = NativeQuery.builder()
                    .withQuery(qb -> qb.matchAll(m -> m))
                    .withPageable(PageRequest.of(pageNum, (int) BATCH_SIZE))
                    .build();
            SearchHits<PostDoc> hits = operations.search(q, PostDoc.class);
            for (SearchHit<PostDoc> h : hits) {
                esIds.add(Long.valueOf(h.getId()));
            }
            if (hits.getSearchHits().size() < BATCH_SIZE) {
                break;
            }
            pageNum++;
        }

        // 2.3 补偿：MySQL 有而 ES 没有 → 补索引
        Set<Long> missing = new HashSet<>(mysqlIds);
        missing.removeAll(esIds);
        if (!missing.isEmpty()) {
            for (Long id : missing) {
                esPostSyncService.indexByPostId(id);
            }
            log.info("ES 对账：补索引 {} 条 {}", missing.size(), missing);
        }

        // 2.4 补偿：ES 有而 MySQL 不应有 → 删索引
        Set<Long> extra = new HashSet<>(esIds);
        extra.removeAll(mysqlIds);
        if (!extra.isEmpty()) {
            for (Long id : extra) {
                esPostSyncService.deleteByPostId(id);
            }
            log.info("ES 对账：删索引 {} 条 {}", extra.size(), extra);
        }

        log.info("ES 对账完成：MySQL 应索引 {} 条，ES 现有 {} 条，补 {} 删 {}", 
                mysqlIds.size(), esIds.size(), missing.size(), extra.size());
    }
}
