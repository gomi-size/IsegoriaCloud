package com.ruwei.rec.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.ruwei.innerservice.InnerInteractionService;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.rec.empty.PostDoc;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 帖子搜索服务（ES 检索，非 MySQL）。
 *
 * <h3>为什么搜索走 ES 而不走 MySQL</h3>
 * <p>关键词要分词（IK）、要相关性排序、要高亮；MySQL 的 {@code LIKE %kw%} 三样都做不到。
 * 索引里是<b>帖子的冗余快照</b>（{@link PostDoc}：标题 / 正文纯文本 / 标签名 / 作者昵称头像 /
 * 各种计数 / score），所以搜出来的结果<b>不用回表</b>就能渲染卡片。</p>
 *
 * <h3>Phase 8 跨服务改造要点</h3>
 * <ul>
 *   <li><b>isLiked 装配</b>：旧 {@code LikeService.batchPostLiked} → Dubbo 调 interaction 的
 *       {@code getPostLikedMap}；loginId 在 HTTP 线程用 {@code StpUtil} 取好再显式传参
 *       （Dubbo provider 线程没有请求上下文）。</li>
 *   <li><b>板块 / 标签装配</b>：旧 {@code BoardBriefFiller} / {@code TagBriefFiller}（依赖 post 域
 *       Service，rec 拿不到）→ Dubbo 调 post 的 {@code fillBoardAndTags}。
 *       ⚠️ 必须<b>接收返回值</b>：Dubbo 跨进程传的是副本，写成当 void 用会编译通过但 board/tags 全空。</li>
 *   <li>查询构造 / 高亮 / 排序 / {@code toBrowseVO} 是纯 ES 逻辑，<b>一字未改</b>。</li>
 * </ul>
 */
@Slf4j
@Service
public class EsSearchService {

    @Resource
    private ElasticsearchOperations operations;

    /** interaction 服务：本页帖子的 {@code isLiked} 批量装配 */
    @DubboReference
    private InnerInteractionService innerInteractionService;

    /** post 服务：本页帖子的板块对象 + 话题标签批量装配（复用 post 侧现成的两个 Filler） */
    @DubboReference
    private InnerPostService innerPostService;

    /**
     * 帖子搜索。
     *
     * <p>匹配逻辑（keyword 非空时三条件取 OR，至少命中其一）：</p>
     * <ol>
     *   <li><b>整词匹配</b>：multiMatch 多字段 title / plainText / tagNames / <b>nickname</b>
     *       （搜用户昵称可命中其公开帖子，如系统默认昵称 {@code ISEGORIA_xxxxxx}）；</li>
     *   <li><b>标题子串模糊</b>：wildcard 对 title 字段做 {@code *keyword*} 任意位置通配，
     *       等效 SQL LIKE %kw%，解决单字/词片段（如「越」→「越狱」）搜不到的问题；</li>
     *   <li><b>昵称子串模糊</b>：wildcard 对 nickname 字段做 {@code *keyword*} 任意位置通配
     *       （IK 分词产出小写 token，查询串统一小写 + 转义通配符），
     *       支持输入半截昵称（如 {@code GhTq}）也能命中。</li>
     * </ol>
     *
     * @param keyword 关键词（可为空，空则按条件浏览）
     * @param boardId 板块过滤（可空）
     * @param type    内容形态过滤（可空）
     * @param sort    排序：score(热度) / time(最新) / 默认相关度
     */
    public Page<PostBrowseVO> searchPost(String keyword, Long boardId, Integer type,
                                         String sort, long current, long pageSize) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    b.filter(f -> f.term(t -> t.field("visibility").value(1))); // 只搜公开
                    if (boardId != null) {
                        b.filter(f -> f.term(t -> t.field("boardId").value(boardId)));
                    }
                    if (type != null) {
                        b.filter(f -> f.term(t -> t.field("type").value(type)));
                    }
                    if (StrUtil.isNotBlank(keyword)) {
                        // 转义 wildcard 特殊字符（\ * ?），小写化对齐 IK 英文 token
                        String escaped = keyword.toLowerCase(Locale.ROOT)
                                .replace("\\", "\\\\")
                                .replace("*", "\\*")
                                .replace("?", "\\?");
                        b.must(m -> m.bool(mb -> mb
                                .should(sh -> sh.multiMatch(mm -> mm
                                        .fields("title", "plainText", "tagNames", "nickname")
                                        .query(keyword)))
                                // 标题子串模糊：等效 LIKE %kw%，解决单字/词片段搜不到（如「越」→「越狱」）
                                .should(sh -> sh.wildcard(w -> w.field("title").value("*" + escaped + "*")))
                                // 昵称子串模糊：支持输入半截昵称
                                .should(sh -> sh.wildcard(w -> w.field("nickname").value("*" + escaped + "*")))
                                .minimumShouldMatch("1")));
                    }
                    return b;
                }))
                .withPageable(PageRequest.of((int) (current - 1), (int) pageSize, buildSort(sort)))
                .withHighlightQuery(buildHighlight())
                .build();

        SearchHits<PostDoc> hits = operations.search(query, PostDoc.class);

        List<PostBrowseVO> list = new ArrayList<>();
        for (SearchHit<PostDoc> hit : hits) {
            PostBrowseVO vo = toBrowseVO(hit.getContent());
            // 高亮片段覆盖 title（含 <em>）
            List<String> titleHL = hit.getHighlightField("title");
            if (titleHL != null && !titleHL.isEmpty()) {
                vo.setTitle(titleHL.get(0));
            }
            // 高亮片段覆盖预览正文（命中上下文片段，天然即摘要，且保留 <em> 命中标记）
            List<String> plainHL = hit.getHighlightField("plainText");
            if (plainHL != null && !plainHL.isEmpty()) {
                vo.setContentPreview(plainHL.get(0));
            }
            list.add(vo);
        }

        Page<PostBrowseVO> page = new Page<>(current, pageSize);
        page.setRecords(list);
        page.setTotal(hits.getTotalHits());

        // 批量填充板块对象 + 话题标签：交给 post 侧现成的两个 Filler（Dubbo 一次往返搞定）
        List<PostBrowseVO> filled = innerPostService.fillBoardAndTags(list);
        if (filled != null) {
            list = filled;
            page.setRecords(list);
        }

        // 当前登录用户批量装配 isLiked（游客跳过；interaction 侧 Redis pipeline 一次往返，防 N+1）
        fillIsLiked(list);

        return page;
    }

    /**
     * 按当前登录用户批量填充 {@code isLiked}（对齐 11-like-module.md §13 末段设计）。
     *
     * <p>isLiked 是用户维度动态状态，不冗余进 ES 索引（会串号），查询后按本页 postIds
     * 一次 Dubbo 调 interaction 的 {@link InnerInteractionService#getPostLikedMap} 装配；
     * 未登录（游客可访问 /search/post）直接跳过，VO.isLiked 保持 null，前端按未赞渲染。</p>
     *
     * <p>loginId 必须在<b>本 HTTP 线程</b>用 {@code StpUtil} 取好再传参——Dubbo provider 线程
     * 没有请求上下文，在 interaction 侧调 {@code StpUtil} 会抛 NotLoginException。</p>
     *
     * @param list 本页已组装的 PostBrowseVO 列表（原地填充，不新建）
     */
    private void fillIsLiked(List<PostBrowseVO> list) {
        if (list == null || list.isEmpty() || !StpUtil.isLogin()) {
            return;
        }
        long loginId = StpUtil.getLoginIdAsLong();
        List<Long> postIds = list.stream()
                .map(PostBrowseVO::getId)
                .filter(Objects::nonNull)
                .toList();
        if (postIds.isEmpty()) {
            return;
        }
        Map<Long, Boolean> likedMap = innerInteractionService.getPostLikedMap(loginId, postIds);
        if (likedMap == null) {
            return;
        }
        list.forEach(vo -> {
            if (vo.getId() != null) {
                vo.setIsLiked(likedMap.getOrDefault(vo.getId(), false));
            }
        });
    }

    private Sort buildSort(String sort) {
        if ("score".equals(sort)) {
            return Sort.by(Sort.Order.desc("score"), Sort.Order.desc("createdAt"));
        }
        if ("time".equals(sort)) {
            return Sort.by(Sort.Order.desc("createdAt"));
        }
        // 默认相关度（_score），ES 默认行为，不传 Sort
        return Sort.unsorted();
    }

    private HighlightQuery buildHighlight() {
        Highlight highlight = new Highlight(HighlightParameters.builder()
                .withPreTags(new String[]{"<em>"})
                .withPostTags(new String[]{"</em>"})
                .build(),
                List.of(new HighlightField("title"),
                        new HighlightField("plainText")));
        return new HighlightQuery(highlight, PostDoc.class);
    }

    private PostBrowseVO toBrowseVO(PostDoc d) {
        PostBrowseVO vo = new PostBrowseVO();
        vo.setId(d.getId());
        vo.setPostCode(d.getPostCode());
        vo.setUserId(d.getUserId());
        vo.setUserNickname(d.getNickname());
        vo.setUserAvatar(d.getAvatar());
        vo.setBoardId(d.getBoardId());
        vo.setTitle(d.getTitle());
        vo.setCover(d.getCover());
        // 默认预览正文：ES 纯文本字段截断（命中关键词时上方用高亮片段覆盖）
        vo.setContentPreview(StrUtil.maxLength(d.getPlainText(), 100));
        vo.setType(d.getType());
        vo.setLikeCount(d.getLikeCount());
        vo.setCommentCount(d.getCommentCount());
        vo.setCollectCount(d.getCollectCount());
        vo.setViewCount(d.getViewCount());
        vo.setIsTop(d.getIsTop());
        vo.setIsEssence(d.getIsEssence());
        vo.setCreatedAt(d.getCreatedAt());
        return vo;
    }
}
