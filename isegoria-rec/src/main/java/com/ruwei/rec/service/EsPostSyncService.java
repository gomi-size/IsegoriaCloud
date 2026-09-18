package com.ruwei.rec.service;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.ContentBlock;
import com.ruwei.model.entity.Post;
import com.ruwei.model.entity.Tag;
import com.ruwei.model.entity.User;
import com.ruwei.model.enums.PostAuditStatusEnum;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;
import com.ruwei.rec.empty.PostDoc;
import com.ruwei.rec.mapper.PostEsMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 帖子 ES 索引同步服务（MySQL → ES 的单向同步链路末端）。
 *
 * <h3>它解决什么问题</h3>
 * <p>ES 索引里存的是帖子的<b>冗余快照</b>（{@link PostDoc}），MySQL 变了 ES 不一定变，
 * 所以要有一条同步链路：</p>
 * <pre>
 * post 服务改帖子（发布 / 下架 / 编辑 / 删除 / 上下架）
 *   → 发 es.post.index（PostIndexMessage）
 *   → rec 的 PostIndexConsumer 收到
 *   → 本类的 indexByPostId / deleteByPostId → 写 / 删 ES 文档
 *   + 每日 3:00 EsReconcileTask 全量对账（MySQL 应索引集合 vs ES 现有集合，少补多删）
 *
 * user 服务改昵称 / 头像 → 发 es.user.profile（UserProfileMessage）
 *   → rec 的 UserProfileConsumer 收到 → 本类 reindexByAuthorId(该作者的全部帖子)
 * </pre>
 *
 * <h3>Phase 8 跨服务改造要点</h3>
 * <ul>
 *   <li>取数由本地 Service 改为 Dubbo 契约：{@code postService} → {@link InnerPostService}、
 *       {@code userService} → {@link InnerUserService}、{@code tagService} →
 *       {@link InnerPostService#listTagsByIds}（tag 表归 post 域，没有独立的 tag 服务）；</li>
 *   <li>{@link #fullReindex()} 由 MP 页码分页改为<b>游标分页</b>
 *       （{@link InnerPostService#listPostIdsAfterId}），避免深分页变慢与一次性 list 全表 OOM；</li>
 *   <li>{@code shouldIndex} / {@code toDoc} / {@code extractPlainText} / {@code tagNamesOf}
 *       的<b>判断与拼装逻辑一字未改</b>，只是取数换了通道。</li>
 * </ul>
 *
 * @author ruwei
 */
@Slf4j
@Service
public class EsPostSyncService {

    /** 全量重建 / 对账的游标批大小（与 EsReconcileTask 保持一致） */
    private static final int BATCH_SIZE = 500;

    @Resource
    private PostEsMapper postEsMapper;

    /** user 服务：{@code PostDoc} 冗余的作者昵称/头像取数 */
    @DubboReference
    private InnerUserService innerUserService;

    /** post 服务：帖子 / 游标分页 / 标签全部归它 */
    @DubboReference
    private InnerPostService innerPostService;

    /**
     * 是否应进入 ES 索引（安全底线，单点判断）：
     * 仅「已发布 + 审核通过 + 公开 + 未删除」的帖子。
     */
    public boolean shouldIndex(Post post) {
        return post != null
                && post.getIsDelete() != null && post.getIsDelete() == 0
                && PostStatusEnum.PUBLISHED.matches(post.getStatus())
                && PostAuditStatusEnum.APPROVED.matches(post.getAuditStatus())
                && PostVisibilityEnum.PUBLIC.matches(post.getVisibility());
    }

    /** 按 id 增量索引（新增/更新） */
    public void indexByPostId(Long postId) {
        Post post = innerPostService.getById(postId);
        if (post == null) {
            return;
        }
        if (!shouldIndex(post)) {
            // 不满足索引条件就确保索引里没有它（防止脏数据）
            postEsMapper.deleteById(postId);
            return;
        }
        postEsMapper.save(toDoc(post));
    }

    /** 按 id 删除索引 */
    public void deleteByPostId(Long postId) {
        postEsMapper.deleteById(postId);
    }

    /**
     * 全量重建：分批扫 post 表，只索引满足 {@link #shouldIndex} 的帖子。
     *
     * <p><b>为什么用游标（{@code WHERE id > lastId ORDER BY id LIMIT n}）而不是页码分页</b>：
     * <ol>
     *   <li>不把 MyBatis-Plus 的 {@code Page} 类型带进 Dubbo 契约；</li>
     *   <li>深分页（{@code limit 500000, 500}）在 MySQL 上会越来越慢，游标走主键索引恒定开销；</li>
     *   <li>扫表期间新增的帖子不会被漏掉（下次从上次的 lastId 继续）。</li>
     * </ol>
     * 旧单体是一次性 {@code page()} 循环扫全表，迁移时必须换成游标（见手册 §6 风险 8：老写法会 OOM）。</p>
     */
    public void fullReindex() {
        long total = 0;
        Long lastId = 0L;
        while (true) {
            // ① 游标取一批 id（升序）；返回空即扫完
            List<Long> ids = innerPostService.listPostIdsAfterId(lastId, BATCH_SIZE);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            // ② 按 id 批量取实体，只索引满足条件的
            List<Post> posts = innerPostService.listByIds(ids);
            List<PostDoc> docs = (posts == null ? List.<Post>of() : posts).stream()
                    .filter(this::shouldIndex)
                    .map(this::toDoc)
                    .collect(Collectors.toList());
            if (!docs.isEmpty()) {
                postEsMapper.saveAll(docs);
                total += docs.size();
            }
            // ③ 游标推进到本批最后一条（ids 升序，末位即最大 id）
            lastId = ids.get(ids.size() - 1);
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        log.info("ES 全量重建完成，共索引 {} 条帖子", total);
    }

    /**
     * 按作者重建索引（用户资料变更时调用，对齐 UserProfileUpdatedEvent 消费端）。
     *
     * <p>该作者全部帖子中满足 {@link #shouldIndex} 的批量重建（作者/标签一次查，避免 N 次回表），
     * 不满足条件的 {@code deleteById} 清理脏索引（防已下架/私密帖残留）。</p>
     *
     * @param userId 作者内部 id（null 直接跳过）
     */
    public void reindexByAuthorId(Long userId) {
        if (userId == null) {
            return;
        }
        List<Post> posts = innerPostService.listPostsByAuthorId(userId);
        if (posts == null || posts.isEmpty()) {
            return;
        }
        // 作者一次查（该作者所有帖子的 nickname/avatar 相同）
        User author = innerUserService.getById(userId);
        // 全部话题 id 一次批量查（避免 toDoc 逐帖查 tag）
        Set<Long> allTagIds = new HashSet<>();
        for (Post p : posts) {
            if (StrUtil.isBlank(p.getTopic())) {
                continue;
            }
            StrUtil.split(p.getTopic(), ',').stream()
                    .filter(NumberUtil::isLong)
                    .map(Long::valueOf)
                    .forEach(allTagIds::add);
        }

        // 查询结果 → tagMap = {1: Tag对象1, 2: Tag对象2, 3: Tag对象3}
        // tag 表归 post 域 → 走 InnerPostService.listTagsByIds（旧写法是本地 tagService.lambdaQuery）
        List<Tag> tags = allTagIds.isEmpty() ? List.of() : innerPostService.listTagsByIds(allTagIds);
        Map<Long, Tag> tagMap = (tags == null ? List.<Tag>of() : tags).stream()
                .collect(Collectors.toMap(Tag::getId, t -> t, (a, b) -> a));

        List<PostDoc> docs = new ArrayList<>();
        for (Post post : posts) {
            if (!shouldIndex(post)) {
                // 不满足索引条件就确保索引里没有它（防止脏数据）
                postEsMapper.deleteById(post.getId());
                continue;
            }
            docs.add(toDoc(post, author, tagMap));
        }
        if (!docs.isEmpty()) {
            postEsMapper.saveAll(docs);
        }
        log.info("用户资料变更触发 ES 重建完成 authorId={} 重建 {} 条", userId, docs.size());
    }

    /**
     * 该作者全部帖子 id（供失败重试队列 {@code es:sync:fail:ids} 写入，由对账任务逐帖重试）。
     *
     * @param userId 作者内部 id
     * @return 该作者全部帖子 id（可能为空列表）
     */
    public List<Long> listPostIdsByAuthor(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Long> ids = innerPostService.listPostIdsByAuthorId(userId);
        return ids == null ? List.of() : ids;
    }

    /** Post -> PostDoc（单条，内部回表查作者/标签，供 indexByPostId / fullReindex 使用） */
    private PostDoc toDoc(Post post) {
        return toDoc(post, innerUserService.getById(post.getUserId()), null);
    }

    /**
     * Post -> PostDoc（批量版，作者/标签由调用方一次性查出传入，避免 N+1）。
     *
     * @param post   帖子实体
     * @param author 已查出的作者（可 null，置空昵称/头像）
     * @param tagMap 已查出的标签索引（可 null，回退逐帖查库）
     */
    private PostDoc toDoc(Post post, User author, Map<Long, Tag> tagMap) {
        PostDoc d = new PostDoc();
        d.setId(post.getId());
        d.setPostCode(post.getPostCode());
        d.setUserId(post.getUserId());
        d.setBoardId(post.getBoardId());
        d.setTitle(post.getTitle());
        d.setPlainText(extractPlainText(post.getContent()));
        d.setTagNames(tagNamesOf(post.getTopic(), tagMap));
        d.setCover(post.getCover());
        d.setType(post.getType());
        d.setVisibility(post.getVisibility());
        d.setStatus(post.getStatus());
        d.setAuditStatus(post.getAuditStatus());
        d.setLikeCount(post.getLikeCount());
        d.setCommentCount(post.getCommentCount());
        d.setCollectCount(post.getCollectCount());
        d.setViewCount(post.getViewCount());
        d.setShareCount(post.getShareCount());
        d.setScore(post.getScore() == null ? 0d : post.getScore().doubleValue());
        d.setIsTop(post.getIsTop());
        d.setIsEssence(post.getIsEssence());
        d.setCreatedAt(post.getCreatedAt());
        // 冗余作者昵称/头像，避免搜索结果回表 N+1
        if (author != null) {
            d.setNickname(author.getNickname());
            d.setAvatar(author.getAvatar());
        }
        return d;
    }

    /** 正文 content（ContentBlock JSON 数组或纯文本）→ 可检索纯文本 */
    private String extractPlainText(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("[")) {
            try {
                List<ContentBlock> blocks = JSONUtil.toList(trimmed, ContentBlock.class);
                return blocks.stream()
                        .filter(b -> StrUtil.isNotBlank(b.getText()))
                        .map(ContentBlock::getText)
                        .collect(Collectors.joining("\n"));
            } catch (Exception e) {
                log.warn("正文 blocks 解析失败，退回纯文本处理: {}", e.getMessage());
                return content;
            }
        }
        return content;
    }

    /** topic（tag id 逗号串）→ 标签名称列表（单条路径，逐帖查库） */
    private List<String> tagNamesOf(String topic) {
        return tagNamesOf(topic, null);
    }

    /**
     * topic（tag id 逗号串）→ 标签名称列表（批量路径）。
     *
     * @param topic  帖子话题串（tag id 逗号分隔）
     * @param tagMap 调用方批量查出的标签索引（null 时回退逐帖查库）
     */
    private List<String> tagNamesOf(String topic, Map<Long, Tag> tagMap) {
        if (StrUtil.isBlank(topic)) {
            return List.of();
        }
        List<Long> ids = StrUtil.split(topic, ',').stream()
                .map(StrUtil::trim)
                .filter(NumberUtil::isLong)
                .map(Long::valueOf)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        if (tagMap != null) {
            // 批量路径：直接从索引取值，不再逐帖查库
            return ids.stream()
                    .map(tagMap::get)
                    .filter(Objects::nonNull)
                    .map(Tag::getName)
                    .collect(Collectors.toList());
        }
        // 单条路径：tag 表归 post 域，走 Dubbo 契约批量查（旧写法是本地 tagService.lambdaQuery）
        List<Tag> tags = innerPostService.listTagsByIds(ids);
        return (tags == null ? List.<Tag>of() : tags).stream()
                .map(Tag::getName)
                .collect(Collectors.toList());
    }
}