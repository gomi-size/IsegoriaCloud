package com.ruwei.post.inner;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ruwei.common.mybatis.CountUtils;
import com.ruwei.innerservice.InnerInteractionService;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.ContentBlock;
import com.ruwei.model.entity.*;
import com.ruwei.model.enums.PostAuditStatusEnum;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.post.assembler.BoardBriefFiller;
import com.ruwei.post.assembler.TagBriefFiller;
import com.ruwei.post.service.BoardService;
import com.ruwei.post.service.CommentService;
import com.ruwei.post.service.PostService;
import com.ruwei.post.service.PostTagService;
import com.ruwei.post.service.TagService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 帖子契约的 Dubbo provider（供 interaction / social / rec / notify 等服务远程调用）。
 *
 * <p>全部为按 id / 条件的幂等读，不校验调用方登录态；
 * 查询条件与旧单体各调用点保持一致（如 {@code listPostIdsByTagIds} 抄自 RecServiceImpl 的召回条件）。</p>
 *
 * <h3>跨服务改造要点（Phase 5 追加的 4 个方法）</h3>
 * <ul>
 *   <li><b>postCode → Post</b>：{@link #getByPostCode(String)} 供 interaction 的点赞链路把前端
 *       postCode 解析为内部 postId（旧单体是 {@code PostServiceImpl} 内部直接 lambdaQuery）。</li>
 *   <li><b>计数不跨服务直改表</b>：{@link #incrementPostCount(Long, String, int)} /
 *       {@link #incrementCommentCount(Long, String, int)} 承接 interaction 的
 *       {@code CountUtils.increment(...)}（旧单体直接注入 {@code IService} 改计数）。
 *       <b>列名走 provider 侧白名单</b>——{@code CountUtils} 是 {@code setSql} 拼串，
 *       列名若由调用方任意传入即成 SQL 注入面。</li>
 *   <li><b>列表装配下沉</b>：{@link #buildBrowseVOList(Long, List)} 供 interaction 的
 *       「我的收藏」列表复用（收藏关系归 interaction，帖子卡片归 post）。</li>
 *   <li><b>登录态显式传参</b>：Dubbo provider 线程无 HTTP 上下文，{@code StpUtil} 不可用，
 *       所有涉及「当前用户」的逻辑一律用入参 {@code loginId}，不得在 provider 内取登录态。</li>
 * </ul>
 *
 * @author ruwei
 */
@Slf4j
@DubboService
public class InnerPostServiceImpl implements InnerPostService {

    /** 帖子计数列白名单：列名会拼进 SQL，必须服务端收口（调用方只传字面常量） */
    private static final Set<String> POST_COUNT_COLUMNS = Set.of("likeCount", "collectCount");

    /** 评论计数列白名单 */
    private static final Set<String> COMMENT_COUNT_COLUMNS = Set.of("likeCount");
    /** 板块计数白名单：列名进 SQL，必须服务端收口 */
    private static final Set<String> BOARD_COUNT_COLUMNS = Set.of("followCount", "postCount");

    /** 列表卡片预览正文最大字符数（超出截断并追加省略号），与 PostServiceImpl 口径一致 */
    private static final int PREVIEW_MAX_LENGTH = 100;

    @Resource
    private PostService postService;

    @Resource
    private CommentService commentService;

    @Resource
    private BoardService boardService;

    @Resource
    private PostTagService postTagService;

    /** tag 表 Service（{@link #listTagsByIds} 装配 ES 文档标签名用） */
    @Resource
    private TagService tagService;

    /** 板块信息批量装配（PostBrowseVO 的 board 对象 + 兼容字段 boardName/boardSlug，防 N+1） */
    @Resource
    private BoardBriefFiller boardBriefFiller;

    /** 话题标签批量装配（PostBrowseVO 的 tags 列表，防 N+1） */
    @Resource
    private TagBriefFiller tagBriefFiller;

    /** user 服务（Dubbo）：列表卡片的作者昵称/头像批量装配 */
    @DubboReference
    private InnerUserService innerUserService;

    /**
     * interaction 服务（Dubbo）：列表卡片的 isLiked / isCollected 批量装配。
     *
     * <p>与 {@code PostServiceImpl} 的注入方式一致（同款 {@code @DubboReference}）；
     * post 与 interaction 互为 provider，Dubbo 侧为懒加载代理，不构成启动期循环依赖。</p>
     */
    @DubboReference
    private InnerInteractionService innerInteractionService;

    @Override
    public Post getById(Serializable id) {
        return postService.getById(id);
    }

    @Override
    public List<Post> listByIds(Collection<? extends Serializable> ids) {
        return postService.listByIds(ids);
    }

    @Override
    public Comment getCommentById(Long commentId) {
        return commentService.getById(commentId);
    }

    @Override
    public Board getBoardById(Long boardId) {
        return boardService.getById(boardId);
    }

    @Override
    public List<Long> listPostIdsByTagIds(List<Long> tagIds) {
        // 空集合短路：避免 MyBatis-Plus 生成非法/无意义的 IN 条件
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        // 抄 RecServiceImpl 召回条件：标签关联（仅已发布版本）→ 去重帖子 id
        return postTagService.lambdaQuery()
                .in(PostTag::getTagId, tagIds)
                .eq(PostTag::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .list().stream().map(PostTag::getPostId).distinct().toList();
    }

    /**
     * 按对外编码查询帖子（postCode → 内部 id 解析）。
     *
     * <p>查询条件与旧单体 {@code LikeServiceImpl.togglePostLike} 完全一致
     * （{@code eq(postCode).one()}，postCode 业务唯一）；返回实体含点赞前校验所需的
     * status / auditStatus / visibility / userId / 计数字段。</p>
     *
     * @param postCode 帖子对外编码（可空）
     * @return 帖子实体；postCode 为空或不存在返回 {@code null}
     */
    @Override
    public Post getByPostCode(String postCode) {
        if (StrUtil.isBlank(postCode)) {
            return null;
        }
        return postService.lambdaQuery()
                .eq(Post::getPostCode, postCode)
                .one();
    }

    /**
     * 帖子冗余计数原子增减（{@code likeCount} / {@code collectCount}）。
     *
     * <p>列名先过白名单再交给 {@link CountUtils#increment}（后者用 {@code setSql} 拼 SQL，
     * 是本项目唯一的计数写入口径，保证并发下不丢更新）；白名单外的列名直接拒绝并告警，
     * 避免调用方把用户输入带进 SQL。</p>
     *
     * @param postId 帖子内部 id
     * @param column 计数列名：仅支持 {@code likeCount} / {@code collectCount}
     * @param delta  增量（正数加、负数减）
     * @return 是否更新成功（影响行数 &gt; 0）
     */
    @Override
    public boolean incrementPostCount(Long postId, String column, int delta) {
        if (postId == null) {
            return false;
        }
        if (!POST_COUNT_COLUMNS.contains(column)) {
            log.warn("拒绝非法帖子计数列名 postId={} column={}（白名单 {}）", postId, column, POST_COUNT_COLUMNS);
            return false;
        }
        return CountUtils.increment(postService, Post::getId, postId, column, delta);
    }

    /**
     * 评论冗余计数原子增减（{@code likeCount}），语义与
     * {@link #incrementPostCount(Long, String, int)} 一致，只是作用在 comment 表。
     *
     * @param commentId 评论内部 id
     * @param column    计数列名：仅支持 {@code likeCount}
     * @param delta     增量（正数加、负数减）
     * @return 是否更新成功（影响行数 &gt; 0）
     */
    @Override
    public boolean incrementCommentCount(Long commentId, String column, int delta) {
        if (commentId == null) {
            return false;
        }
        if (!COMMENT_COUNT_COLUMNS.contains(column)) {
            log.warn("拒绝非法评论计数列名 commentId={} column={}（白名单 {}）",
                    commentId, column, COMMENT_COUNT_COLUMNS);
            return false;
        }
        return CountUtils.increment(commentService, Comment::getId, commentId, column, delta);
    }

    /**
     * 批量装配列表卡片 VO（按入参 postIds 顺序返回，已过滤非「已发布」帖）。
     *
     * <p>装配链路：批量查帖 → 过滤「已发布」并按入参顺序重排 → 作者批量装配（Dubbo 查 user）
     * → 转 VO（含正文预览）→ 板块 / 标签批量装配 → 用户维度状态批量装配（Dubbo 查 interaction）。</p>
     *
     * <p>与 {@code PostServiceImpl.buildBrowseVOList} 的分工：本方法面向跨服务调用方，
     * **登录态必须由入参传入**；post 服务自身 HTTP 链路仍走服务内的同名方法。</p>
     *
     * @param loginId 当前登录用户内部 id（可空；空表示游客视角，不填 isLiked / isCollected）
     * @param postIds 帖子内部 id 列表（有序）
     * @return 同序卡片 VO 列表；入参为空或无有效帖子返回空列表
     */
    @Override
    public List<PostBrowseVO> buildBrowseVOList(Long loginId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        // ① 批量查帖（逻辑删除的帖子由 Post.isDelete 上的 @TableLogic 自动排除）
        List<Post> posts = postService.listByIds(postIds);
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        // ② 只保留「已发布」版本，并按 id 建索引
        //    toMap 必须带合并函数：IN 查询若返回重复行（脏数据）会抛 IllegalStateException
        Map<Long, Post> postMap = posts.stream()
                .filter(p -> p != null && Objects.equals(p.getStatus(), PostStatusEnum.PUBLISHED.getCode()))
                .collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
        if (postMap.isEmpty()) {
            return List.of();
        }
        // ③ IN 查询无序 → 按入参 postIds 重排，调用方的排序语义（如收藏时间倒序）由此保留；
        //    查不到的（已删 / 非已发布）跳过
        List<Post> ordered = postIds.stream()
                .map(postMap::get)
                .filter(Objects::nonNull)
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        // ④ 作者批量装配（一次 Dubbo 调用，防 N+1）
        Map<Long, User> userMap = loadAuthors(ordered);
        // ⚠️ 必须用 Collectors.toList()（可变列表）：Stream.toList() 返回不可变列表，
        //    后续 boardBriefFiller / tagBriefFiller 会原地 set，抛 UnsupportedOperationException
        List<PostBrowseVO> voList = ordered.stream()
                .map(p -> buildPostBrowseVO(p, userMap))
                .collect(Collectors.toList());
        // ⑤ 板块 / 标签批量装配（两个组件内部各自批量查表，防 N+1；无数据时 VO 字段为 null / 空列表）
        boardBriefFiller.fillBoardBrief(voList);
        tagBriefFiller.fillTags(voList);
        // ⑥ 用户维度动态状态（显式传 loginId，provider 线程不能用 StpUtil）
        fillUserState(voList, loginId);
        return voList;
    }

    // ==================== 内部工具 ====================

    /**
     * 批量查询作者并建索引（内部 id → User），供卡片 VO 填充昵称/头像。
     *
     * <p>作者可能已被删除，故查不到的 userId 在 {@code userMap} 中缺失，调用方置空处理。</p>
     *
     * @param posts 已排序的帖子列表
     * @return 作者索引；无作者或远端返回空时返回空 Map（不返回 null）
     */
    private Map<Long, User> loadAuthors(List<Post> posts) {
        List<Long> authorIds = posts.stream()
                .map(Post::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = innerUserService.listByIds(authorIds);
        if (users == null || users.isEmpty()) {
            return Map.of();
        }
        return users.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    /**
     * 批量填充「当前用户是否赞过 / 是否收藏」（各一次 Dubbo 往返，interaction 侧走 Redis pipeline）。
     *
     * @param voList  已组装的卡片 VO（原地填充）
     * @param loginId 当前登录用户内部 id；{@code null} 表示游客 → 直接返回，两个字段保持 {@code null}
     */
    private void fillUserState(List<PostBrowseVO> voList, Long loginId) {
        if (loginId == null || voList == null || voList.isEmpty()) {
            return;
        }
        List<Long> postIds = voList.stream()
                .map(PostBrowseVO::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (postIds.isEmpty()) {
            return;
        }
        Map<Long, Boolean> likedMap = safeMap(innerInteractionService.getPostLikedMap(loginId, postIds));
        Map<Long, Boolean> collectedMap = safeMap(innerInteractionService.getPostCollectedMap(loginId, postIds));
        voList.forEach(vo -> {
            if (vo.getId() == null) {
                return;
            }
            vo.setIsLiked(likedMap.getOrDefault(vo.getId(), false));
            vo.setIsCollected(collectedMap.getOrDefault(vo.getId(), false));
        });
    }

    /** 远端 Map 结果的空值兜底（避免 Dubbo 异常返回 null 时 NPE） */
    private Map<Long, Boolean> safeMap(Map<Long, Boolean> map) {
        return map == null ? Map.of() : map;
    }

    /**
     * 实体 → 列表卡片 VO（与 {@code PostServiceImpl.buildPostBrowseVO} 逐行对齐）。
     *
     * <p>仅装配卡片渲染所需轻量字段；作者昵称/头像取自批量查询的 {@code userMap}
     * （查不到作者时置空，不抛错）。不查 post_image、不解析 topic、不回显枚举文字。</p>
     *
     * @param post    帖子实体（调用方已保证非 null）
     * @param userMap 作者索引（内部 id → User），非 null
     * @return 列表卡片 VO
     */
    private PostBrowseVO buildPostBrowseVO(Post post, Map<Long, User> userMap) {
        PostBrowseVO vo = BeanUtil.copyProperties(post, PostBrowseVO.class);
        vo.setContentPreview(buildContentPreview(post.getContent()));
        User author = userMap.get(post.getUserId());
        if (author != null) {
            vo.setUserNickname(author.getNickname());
            vo.setUserAvatar(author.getAvatar());
        }
        return vo;
    }

    /**
     * 抽取正文纯文本并截断为列表卡片预览摘要（与 {@code PostServiceImpl.buildContentPreview} 对齐）。
     *
     * <p>新数据 {@code content} 为 ContentBlock 的 JSON 数组，拼接各文本块的 {@code text}；
     * 旧数据为纯文本直接使用。统一截断到 {@value #PREVIEW_MAX_LENGTH} 字符（超出追加省略号），
     * 无文本返回 null（前端可降级显示封面图）。</p>
     *
     * @param content 帖子正文字段（JSON 块数组或纯文本，可空）
     * @return 预览正文（无内容返回 null）
     */
    private String buildContentPreview(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        String plain = content.trim();
        // 新数据：content 是以 '[' 开头的 JSON 数组，提取各文本块 text 拼接
        if (plain.startsWith("[")) {
            try {
                List<ContentBlock> blocks = JSONUtil.toList(JSONUtil.parseArray(content), ContentBlock.class);
                StringBuilder sb = new StringBuilder();
                for (ContentBlock b : blocks) {
                    if (StrUtil.isNotBlank(b.getText())) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(b.getText().trim());
                    }
                }
                plain = sb.toString();
            } catch (Exception ignored) {
                // 解析失败（脏数据），fallback 到下方纯文本截断
            }
        }
        if (StrUtil.isBlank(plain)) {
            return null;
        }
        // 截断：超过 PREVIEW_MAX_LENGTH 字符时追加省略号
        if (plain.length() > PREVIEW_MAX_LENGTH) {
            return StrUtil.sub(plain, 0, PREVIEW_MAX_LENGTH) + "...";
        }
        return plain;
    }
    @Override
    public List<Board> listBoardsByIds(Collection<Long> boardIds) {
        if (boardIds == null || boardIds.isEmpty()) {
            return List.of();
        }
        // 逻辑删除板块由 Board 上的 @TableLogic 自动排除
        List<Board> boards = boardService.listByIds(boardIds);
        return boards == null ? List.of() : boards;
    }

    @Override
    public List<Long> listBoardIdsByCreatorId(Long creatorId) {
        if (creatorId == null) {
            return List.of();
        }
        return boardService.lambdaQuery()
                .eq(Board::getCreatorId, creatorId)
                .list().stream()
                .map(Board::getId)
                .toList();
    }

    @Override
    public boolean incrementBoardCount(Long boardId, String column, int delta) {
        if (boardId == null || !BOARD_COUNT_COLUMNS.contains(column)) {
            log.warn("拒绝非法板块计数列名 boardId={} column={}（白名单 {}）", boardId, column, BOARD_COUNT_COLUMNS);
            return false;
        }
        return CountUtils.increment(boardService, Board::getId, boardId, column, delta);
    }

    // ==================== Phase 8：推荐召回 + ES 同步 ====================

    /** 单个查询的兜底上限，防调用方传 0 或负数把整库捞出来 */
    private static final int MAX_RECALL_LIMIT = 500;

    /** 标题/正文等组装成卡片时用不到，这里只取 id */
    private List<Long> toIdList(List<Post> posts) {
        return posts.stream().map(Post::getId).filter(Objects::nonNull).toList();
    }

    /** 归一化 limit：<=0 视为不限制（给个兜底上限），防止误传把库捞空 */
    private int safeLimit(int limit) {
        return limit <= 0 ? MAX_RECALL_LIMIT : Math.min(limit, MAX_RECALL_LIMIT);
    }

    @Override
    public List<Long> listVisiblePostIdsByAuthorIds(Collection<Long> authorIds,
                                                    boolean includeFansOnly, int limit) {
        if (authorIds == null || authorIds.isEmpty()) {
            return List.of();
        }
        // 路①：关注关系背书 → 允许"仅粉丝可见"；其余路一律只放行公开
        List<Integer> visibilities = includeFansOnly
                ? List.of(PostVisibilityEnum.PUBLIC.getCode(), PostVisibilityEnum.FANS_ONLY.getCode())
                : List.of(PostVisibilityEnum.PUBLIC.getCode());
        return toIdList(postService.lambdaQuery()
                .in(Post::getUserId, authorIds)
                .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .eq(Post::getAuditStatus, PostAuditStatusEnum.APPROVED.getCode())
                .in(Post::getVisibility, visibilities)
                .orderByDesc(Post::getCreatedAt)
                .last("limit " + safeLimit(limit))
                .list());
    }

    @Override
    public List<Long> listVisiblePostIdsByBoardIds(Collection<Long> boardIds, int limit) {
        if (boardIds == null || boardIds.isEmpty()) {
            return List.of();
        }
        return toIdList(postService.lambdaQuery()
                .in(Post::getBoardId, boardIds)
                .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .eq(Post::getAuditStatus, PostAuditStatusEnum.APPROVED.getCode())
                .eq(Post::getVisibility, PostVisibilityEnum.PUBLIC.getCode())
                .orderByDesc(Post::getCreatedAt)
                .last("limit " + safeLimit(limit))
                .list());
    }

    @Override
    public List<Long> listHotVisiblePostIds(int limit) {
        return toIdList(postService.lambdaQuery()
                .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .eq(Post::getAuditStatus, PostAuditStatusEnum.APPROVED.getCode())
                .eq(Post::getVisibility, PostVisibilityEnum.PUBLIC.getCode())
                .orderByDesc(Post::getScore)          // 热点路按热度分，不是时间
                .last("limit " + safeLimit(limit))
                .list());
    }

    @Override
    public List<Long> listVisiblePostIdsByIds(Collection<Long> postIds, int limit) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        return toIdList(postService.lambdaQuery()
                .in(Post::getId, postIds)
                .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .eq(Post::getAuditStatus, PostAuditStatusEnum.APPROVED.getCode())
                .eq(Post::getVisibility, PostVisibilityEnum.PUBLIC.getCode())
                .orderByDesc(Post::getCreatedAt)
                .last("limit " + safeLimit(limit))
                .list());
    }

    @Override
    public List<Long> listColdStartPostIds(int hours, int maxViewCount, int limit) {
        // hours <= 0 时给个默认 72h（旧实现的历史默认值，见 RecServiceImpl 注释）
        int windowHours = hours <= 0 ? 72 : hours;
        Date after = DateUtil.offsetHour(new Date(), -windowHours);
        return toIdList(postService.lambdaQuery()
                .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .eq(Post::getAuditStatus, PostAuditStatusEnum.APPROVED.getCode())
                .eq(Post::getVisibility, PostVisibilityEnum.PUBLIC.getCode())
                .gt(Post::getCreatedAt, after)
                .lt(Post::getViewCount, maxViewCount)
                .orderByDesc(Post::getCreatedAt)
                .last("limit " + safeLimit(limit))
                .list());
    }

    @Override
    public List<Long> listPostIdsAfterId(Long lastId, int limit) {
        int size = safeLimit(limit);
        return postService.lambdaQuery()
                .gt(lastId != null && lastId > 0, Post::getId, lastId)   // 首批（null/0）不带条件
                .orderByAsc(Post::getId)
                .last("limit " + size)
                .list().stream()
                .map(Post::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<Post> listPostsByAuthorId(Long authorId) {
        if (authorId == null) {
            return List.of();
        }
        // 不带 status/visibility 过滤：调用方（ES 重建）要自己判断"该索引还是该删"
        return postService.lambdaQuery()
                .eq(Post::getUserId, authorId)
                .list();
    }

    @Override
    public List<Long> listPostIdsByAuthorId(Long authorId) {
        if (authorId == null) {
            return List.of();
        }
        return postService.lambdaQuery()
                .eq(Post::getUserId, authorId)
                .list().stream()
                .map(Post::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<PostBrowseVO> fillBoardAndTags(List<PostBrowseVO> voList) {
        if (voList == null || voList.isEmpty()) {
            return voList;
        }
        // 复用本服务既有的两个装配组件（它们依赖 BoardService / PostTagService / TagService，都在 post 域）
        boardBriefFiller.fillBoardBrief(voList);
        tagBriefFiller.fillTags(voList);
        return voList;
    }

    /**
     * 批量按 id 查标签实体（rec 侧重建 ES 文档、把 {@code post.topic} 的 tag id 串翻译成标签名用）。
     *
     * <p><b>刻意不过滤 {@code tag.status}</b>：ES 索引是帖子内容的历史快照，标签被运营禁用后
     * 不应让既有索引里的标签名凭空消失（对外展示口径的 status 过滤在
     * {@code TagBriefFiller} 里，两者用途不同）。逻辑删除的标签由 {@code @TableLogic} 自动排除。</p>
     *
     * @param tagIds 标签内部 id 集合
     * @return 标签实体列表；入参为空或无命中返回空列表（不返回 null，便于调用方直接 stream）
     */
    @Override
    public List<Tag> listTagsByIds(Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        List<Tag> tags = tagService.listByIds(tagIds);
        return tags == null ? List.of() : tags;
    }
}
