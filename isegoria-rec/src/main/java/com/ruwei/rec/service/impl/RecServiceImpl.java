package com.ruwei.rec.service.impl;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.innerservice.InnerFollowService;
import com.ruwei.innerservice.InnerInteractionService;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.ContentBlock;
import com.ruwei.model.dto.RecExposureDTO;
import com.ruwei.model.dto.RecFeedDTO;
import com.ruwei.model.dto.RecFeedbackDTO;
import com.ruwei.model.entity.*;
import com.ruwei.model.enums.PostAuditStatusEnum;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.model.vo.RecFeedVO;
import com.ruwei.rec.manager.RecCacheManager;
import com.ruwei.rec.service.RecService;
import com.ruwei.rec.service.UserInterestService;
import com.ruwei.rec.service.UserbehaviorService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐流服务（Phase 1 规则驱动四层漏斗：召回 → 粗排 → 精排 → 重排）。
 *
 * <p>接口形态为<b>游标分页</b>：cursor = 上页最后一条的 postId（字符串，全局 ToStringSerializer
 * 防雪花精度丢失），首屏不传；返回 {@link RecFeedVO}（本页列表 + nextCursor + hasMore），
 * 返回条数 &lt; pageSize 即无更多。</p>
 *
 * <h3>Phase 8 跨服务改造要点（本类是全项目跨域依赖最多的类）</h3>
 * <p>四层漏斗里<b>只有「① 召回」跨了服务边界</b>——召回要查 {@code post} 表（该表归 post 服务），
 * 而 ② 过滤（曝光去重）/ ③ 精排 / ④ 重排 全在 rec 域内（Redis + rec 自己的两张表），逐字未动。</p>
 * <p>改造后本类只注入 <b>4 个 Dubbo 引用</b>（social / post / user / interaction），
 * 旧单体的 11 个本地 Service 字段全部收敛：
 * <ul>
 *   <li><b>口径收口</b>：可见性（已发布 + 审核通过 + 公开/仅粉丝）的判断全部下沉到 post 侧的
 *       {@code listVisiblePostIdsBy*} 系列契约方法，不再是"rec 拼 Wrapper"——以后 post 改口径只改一处；</li>
 *   <li><b>召回返回值化</b>：契约直接返回 {@code List<Long>}（只取 id），不再回传整个 Post 实体，
 *       省掉召回阶段的宽表传输；</li>
 *   <li><b>装配复用</b>：{@code PostBrowseVO} 的 board / tags 由 post 侧现成的
 *       {@code BoardBriefFiller} / {@code TagBriefFiller} 填充（这两个组件依赖 post 域 Service，
 *       rec 拿不到，见 {@link InnerPostService#fillBoardAndTags}）。</li>
 * </ul>
 * </p>
 *
 * <p><b>易错点</b>：{@code fillBoardAndTags} 是 Dubbo 跨进程调用，入参是副本，
 * <b>必须接收返回值</b>（{@code voList = innerPostService.fillBoardAndTags(voList);}）；
 * 写成当 {@code void} 用会编译通过但卡片上 board / tags 全空（静默失效）。</p>
 */
@Service
public class RecServiceImpl implements RecService {

    // ---- 精排亲和系数（application.yml rec.* 收口，调参无需改代码） ----
    /** 标签亲和系数 α */
    @Value("${rec.fine.topic:0.3}")
    private double alphaTag;
    /** 内容形态亲和系数 β */
    @Value("${rec.fine.type:0.2}")
    private double betaType;
    /** 板块亲和系数 γ */
    @Value("${rec.fine.board:0.1}")
    private double gammaBoard;
    /** 粗排热度截断条数 */
    @Value("${rec.rough.top:300}")
    private int roughTop;
    // ---- 各路召回上限 ----
    @Value("${rec.recall.follow-limit:200}")
    private int recallFollowLimit;
    @Value("${rec.recall.board-limit:200}")
    private int recallBoardLimit;
    @Value("${rec.recall.hot-limit:100}")
    private int recallHotLimit;
    @Value("${rec.recall.tag-limit:150}")
    private int recallTagLimit;
    @Value("${rec.recall.cold-limit:100}")
    private int recallColdLimit;
    /** 冷启动召回窗口（小时）：审核通过延迟的新帖也能被召回（默认 72h，原硬编码 24h） */
    @Value("${rec.cold.window-hours:72}")
    private int coldWindowHours;
    /** 冷启动 viewCount 阈值：低于该值视为新帖候选（可配 rec.cold.view-threshold） */
    @Value("${rec.cold.view-threshold:50}")
    private int coldViewThreshold;
    /** 冷启动帖精排保底分：新帖无互动（pScore≈0）垫底进不了首屏，保底让其排到 score≈coldFloor 位置 */
    @Value("${rec.fine.cold-floor:1.0}")
    private double coldFloor;
    /** 兴趣维度常量（与 user_interest.dimension 对齐） */
    private static final int DIM_TAG = 2;
    private static final int DIM_TYPE = 3;
    private static final int DIM_BOARD = 4;
    private static final int DIM_AUTHOR = 5;

    /** 列表摘要截断长度（对齐 PostServiceImpl.PREVIEW_MAX_LENGTH，其为本类私有故同值复制） */
    private static final int PREVIEW_MAX_LENGTH = 100;

    /** 单页上限（防一次拉爆内存） */
    private static final int MAX_PAGE_SIZE = 20;
    private static final int DEFAULT_PAGE_SIZE = 10;

    // ==================== 跨服务依赖（Phase 8：4 个 Dubbo 引用，取代旧单体的 11 个本地 Service） ====================

    /**
     * social 服务：关注关系。
     *
     * <p>关注<b>用户</b>（{@link InnerFollowService#listFolloweeIds}）与关注<b>板块</b>
     * （{@link InnerFollowService#listFollowedBoardIds}）都归 social 域，故共用同一个引用。</p>
     *
     * <p>内置领域知识：这两个方法的数据源<b>刻意不同</b>——{@code listFolloweeIds} 读 Redis 热索引
     * （对齐旧 {@code FollowCacheManager.getFollower}，召回对实时性要求高），
     * {@code listFollowedBoardIds} 走社交本域的 board_follow 表。不要"统一"它们。</p>
     */
    @DubboReference
    private InnerFollowService innerFollowService;

    /**
     * post 服务：召回取数（5 路）+ 帖子详情 + 装配板/标签。
     *
     * <p>注意：{@code postService} / {@code postTagService} 两个旧字段都收敛到这里
     * （标签关联 post_tag 表也归 post 域）。</p>
     */
    @DubboReference
    private InnerPostService innerPostService;

    /** user 服务：卡片作者昵称/头像批量装配（旧 {@code userService.listByIds} 的替代） */
    @DubboReference
    private InnerUserService innerUserService;

    /**
     * interaction 服务：卡片 {@code isLiked} / {@code isCollected} 批量装配。
     *
     * <p>旧字段 {@code likeService} / {@code collectService} 两个都收敛到这里。
     * 传参约定：loginId 由 HTTP 线程取好显式传入（Dubbo provider 线程无 {@code StpUtil} 上下文）。</p>
     */
    @DubboReference
    private InnerInteractionService innerInteractionService;

    // ==================== rec 域内依赖（零改造） ====================

    @Resource
    private UserInterestService userInterestService;
    @Resource
    private RecCacheManager recCacheManager;
    @Resource
    private UserbehaviorService userbehaviorService;


    /**
     * 精排中间载体：帖子 + 精排分（hotScore × 亲和放大）。
     */
    private record Scored(Post post, double pScore) {}

    /**
     * 推荐流（游标分页）。
     *
     * <p>登录用户：tab=recommend 走四层漏斗个性化；tab=discover 只走热点+冷启动。
     * 游客：强制降级 discover 口径（不读关注/板块/标签画像，不写曝光）。</p>
     *
     * @param req 游标分页请求（cursor / pageSize / tab）
     * @return 当前页结果（帖子卡片 + nextCursor + hasMore，已按重排规则排序，返回前已回写曝光）
     */
    @Override
    public RecFeedVO feed(RecFeedDTO req) {
        if (req == null) {
            req = new RecFeedDTO();
        }
        // 0. 参数兜底 + 登录态
        int pageSize = clampPageSize(req.getPageSize());
        boolean guest = !StpUtil.isLogin();
        Long loginId = guest ? null : StpUtil.getLoginIdAsLong();
        // 游客强制 discover 口径（不读关注/板块/画像）；discover tab 同样只走热点+冷启动
        boolean discover = guest || "discover".equals(req.getTab());

        // ① 召回五路并集（LinkedHashSet 保序去重：关注→板块→热点→标签→冷启动，热点在中间保底）
        Set<Long> candidateIds = new LinkedHashSet<>();
        if (!discover) {
            recallByFollow(loginId, candidateIds);
            recallByBoard(loginId, candidateIds);
        }
        recallByHot(candidateIds);
        if (!discover) {
            recallByTag(loginId, candidateIds);
        }
        Set<Long> coldIds = recallByCold(candidateIds);
        if (candidateIds.isEmpty()) {
            // 无任何候选 → 无新内容可推：登录用户回显最近推流过的内容兜底不空屏；游客无历史保持空页
            return fallbackToExposed(loginId, pageSize);
        }


        // 粗排：批量取帖（@TableLogic 自动过滤已删）→ 状态双保险 → 热度降序截断 Top N
        // 注意：此处不剔除已曝光帖——游标定位需要「含已曝光的全量精排序列」保持跨页稳定，
        // 曝光剔除下沉到下面的「取页」阶段（跳过已曝光 + 顺延补齐 pageSize 条）
        List<Post> posts = innerPostService.listByIds(candidateIds).stream().filter(this::isRecommendable)
                .sorted(Comparator.comparingDouble(this::hotScore).reversed())
                .toList();

        //需要一个截断，防止一次性推送过多
        if(posts.size()>roughTop){
            posts=posts.subList(0,roughTop);
        }
        if (posts.isEmpty()) {
            // 候选全被状态过滤（竞态下架/审核撤回）→ 回显最近推流过的内容兜底
            return fallbackToExposed(loginId, pageSize);
        }
        //精排：pScore = hotScore × (1 + α·tag亲和 + β·type亲和 + γ·board亲和)
        //主要是处理一下兴趣表的事情
        Map<String,Double> profile = guest ? Map.of() : loadProfile(loginId);
        //处理帖子,根据用户长期兴趣画像转化为：帖子id，分数（类似map集合）
        //冷启动帖 pScore 保底：新帖无互动（score≈0 → pScore≈0）会垫底排在候选末尾进不了首屏，
        //保底分让新帖至少排到 score≈coldFloor 位置，审核通过的新帖才能被看到
        List<Scored> scored = posts.stream().map(p -> new Scored(p, calcPScore(p, profile)))
                .map(s -> coldIds.contains(s.post().getId())
                        ? new Scored(s.post(), Math.max(s.pScore(), coldFloor))
                        : s)
                .sorted(Comparator.comparingDouble(Scored::pScore).reversed())
                .toList();

        // 重排 1/4：游标定位（在全量精排序列中定位，序列含已曝光帖 → cursor 精确匹配不失效）
        int start = locateCursor(scored, req.getCursor());
        if(start>=scored.size()){
            // 游标越界（末页后继续翻页）→ 回显最近推流过的内容兜底
            return fallbackToExposed(loginId, pageSize);
        }
        // 重排 2/4：取页 = 从 start 起跳过已曝光帖、顺延补齐 pageSize 条
        // 游客无曝光档案直接取连续页；登录用户 Redis 曝光剔除（feed:exposure:{uid} ZSet，4 天 TTL）：
        // 会话曝光（feed 返回时自动回写 + recordExposure 兜底）与负反馈屏蔽共用同一 ZSet，
        // 已曝光帖（含负反馈帖）一律跳过顺延补齐，永不作为「新内容」放出；取页为空（已看尽）时
        // 由 fallbackToExposed 回显最近推流过的内容兜底不空屏（登录用户；游客无历史仍空页）
        List<Post> pagePosts = new ArrayList<>();
        if (guest) {
            for (int i = start; i < scored.size() && pagePosts.size() < pageSize; i++) {
                pagePosts.add(scored.get(i).post());
            }
        } else {
            List<Long> tailIds = new ArrayList<>();
            for (int i = start; i < scored.size(); i++) {
                tailIds.add(scored.get(i).post().getId());
            }
            // Redis 剔除：filterExposed 返回 tailIds 中「未被曝光过」的帖（pipeline 批量 zScore），
            // 已曝光（会话已看 + 负反馈屏蔽）不在此集合内，取页时直接跳过
            Set<Long> unexposed = tailIds.isEmpty() ? Set.of()
                    : recCacheManager.filterExposed(loginId, tailIds);
            for (int i = start; i < scored.size() && pagePosts.size() < pageSize; i++) {
                Post p = scored.get(i).post();
                if (unexposed.contains(p.getId())) {
                    pagePosts.add(p);
                }
            }
        }
        if (pagePosts.isEmpty()) {
            // 曝光剔除后本页无可放内容（已看尽）→ 回显最近推流过的内容兜底不空屏（登录用户）
            return fallbackToExposed(loginId, pageSize);
        }
        // 会话曝光服务端回写：本页帖子写入 feed:exposure:{uid} ZSet（score=时间戳，ZADD 幂等，
        //4天 TTL），下次取页 filterExposed 剔除（跨刷新持久）；前端 recordExposure 仅作弱网兜底
        if (!guest) {
            recCacheManager.addExposures(loginId, pagePosts.stream().map(Post::getId).toList());
        }

        //重排 3/4：页内重排（同作者打散 → 冷启动注入位 5/8）
        List<Post> reordered = rerank(pagePosts, coldIds);

        // 自建装配（同构 PostServiceImpl，不动其 private 方法）
        List<PostBrowseVO> voList = assemble(reordered, loginId);

        // 游标翻页语义：前端取本页最后一条 id 作下次 cursor；本页不足 pageSize 即无更多
        RecFeedVO vo = new RecFeedVO();
        vo.setPostBrowseVOList(voList);
        boolean hasMore = pagePosts.size() >= pageSize;
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore ? reordered.get(reordered.size() - 1).getId() : null);
        return vo;
    }

    /**
     * 空页结果：空列表 + hasMore=false + nextCursor=null。
     * 仅剩<b>游客</b>（不写曝光档案，无历史可回显）与「回显内容亦为空」的场景使用；
     * 登录用户推荐流空页由 {@link #fallbackToExposed} 回显最近推流过的内容兜底，不空屏。
     */
    private RecFeedVO emptyFeed() {
        RecFeedVO vo = new RecFeedVO();
        vo.setPostBrowseVOList(List.of());
        vo.setHasMore(false);
        vo.setNextCursor(null);
        return vo;
    }

    /**
     * 空页回显兜底（登录用户「不空屏」）：无新内容可推（候选耗尽/已看尽/游标越界/取页为空）时，
     * 从 Redis 曝光档案取<b>最近推流过的帖子</b>原样返回（最近曝光在前，最多 pageSize 条），
     * 让前端继续展示已推流内容，替代上一阶段「空页由前端本地列表保留」的方案。
     *
     * <p>边界：
     * <ul>
     *   <li>游客不写曝光档案 → 直接空页（{@link #emptyFeed()}），行为与原先一致；</li>
     *   <li>回显的帖子本就在曝光档案中，<b>不再重复回写曝光</b>（ZADD 虽幂等，但会刷新
     *       score=时间戳，导致同一批帖永远排在回显头部、反复霸屏）；</li>
     *   <li>回显仍过 {@link #isRecommendable} 状态过滤（已下架/审核撤回的不回显），
     *       再按曝光时间序（最近在前）重排装配，保证顺序稳定；</li>
     *   <li><b>已知限制</b>：曝光档案与负反馈屏蔽共用同一 ZSet，历史点过「不感兴趣」的帖子
     *       也会被回显。如需排除，需另建独立屏蔽档案（如 feed:block:{uid}）并在本方法剔除，
     *       当前阶段暂不区分。</li>
     * </ul>
     *
     * @param loginId  当前登录用户内部 id（游客为 null）
     * @param pageSize 回显条数上限
     * @return 回显结果（hasMore=false、nextCursor=null，前端翻页终止）；无历史/游客时为空页
     */
    private RecFeedVO fallbackToExposed(Long loginId, int pageSize) {
        if (loginId == null) {
            return emptyFeed();
        }
        List<Long> exposed = recCacheManager.get(loginId, pageSize);
        if (CollUtil.isEmpty(exposed)) {
            return emptyFeed();
        }
        List<Post> posts = innerPostService.listByIds(exposed).stream()
                .filter(this::isRecommendable)
                .toList();
        if (posts.isEmpty()) {
            return emptyFeed();
        }
        // listByIds 无序，按曝光时间序（最近曝光在前）重排，保证回显顺序稳定且与 get 一致
        Map<Long, Post> byId = posts.stream()
                .collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
        List<Post> ordered = exposed.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        if (ordered.isEmpty()) {
            return emptyFeed();
        }
        RecFeedVO vo = new RecFeedVO();
        // 回显不写曝光（见边界说明）；hasMore=false 终止前端继续翻页，避免无限回显
        vo.setPostBrowseVOList(assemble(ordered, loginId));
        vo.setHasMore(false);
        vo.setNextCursor(null);
        return vo;
    }

    /**
     * 兜底曝光回写：feed 返回时已自动把本页帖子写入 feed:exposure:{uid}（ZADD 幂等），
     * 前端滚动渲染/弱网重试丢页时可再调本接口补写，防漏剔（幂等：同 member 只刷新时间戳）。
     */
    @Override
    public void recordExposure(RecExposureDTO req) {
        recCacheManager.addExposures(StpUtil.getLoginIdAsLong(), req.getPostIds());
    }

    /**
     * 负反馈
     * @param req 帖子内部 id（字符串） + 负反馈类型
     */
    @Override
    public void feedback(RecFeedbackDTO req) {

        ThrowUtils.throwIf(req == null || req.getPostId()==null||req.getPostId()<0L,
                ErrorCode.PARAMS_ERROR, "参数不能为空");

        long loginId = StpUtil.getLoginIdAsLong();
        long postId = req.getPostId();
        Post post = innerPostService.getById(postId);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 1. 写行为日志 action=8 负反馈（extras 带反馈类型，离线分析用）
        userBehavior ub = new userBehavior();
        ub.setUserId(loginId);
        ub.setPostId(postId);
        ub.setAction(8);
        ub.setSource(1);
        ub.setPosition(0);
        ub.setDwellSec(0);
        ub.setExtras("{\"feedbackType\":" + (req.getType() == null ? 0 : req.getType()) + "}");
        userbehaviorService.save(ub);
        // 2. 短期兴趣降权：该帖全部兴趣维度 INCR -1（读取侧 max(0) 兜底，Phase 1 接受负值，§10.3）
        incrInterestsOfPost(loginId, post, -1.0);
        // 3. 曝光屏蔽：负反馈帖直接写入曝光集合（7 天内不再进入推荐流，等价「看过了且不感兴趣」，
        //    与 feed 页内自动回写同一 ZSet，ZADD 幂等无副作用）
        recCacheManager.addExposures(loginId, List.of(postId));
    }

    /** 帖子 → 兴趣维度批量 INCR（dim2 标签 / dim3 类型 / dim4 板块 / dim5 作者）。 */
    private void incrInterestsOfPost(Long uid, Post post, double delta) {
        if (StrUtil.isNotBlank(post.getTopic())) {
            for (String tag : StrUtil.split(post.getTopic(), ',')) {
                String t = StrUtil.trim(tag);
                if (NumberUtil.isLong(t)) {
                    recCacheManager.incrInterest(uid, DIM_TAG, t, delta);
                }
            }
        }
        if (post.getType() != null) {
            recCacheManager.incrInterest(uid, DIM_TYPE, String.valueOf(post.getType()), delta);
        }
        if (post.getBoardId() != null) {
            recCacheManager.incrInterest(uid, DIM_BOARD, String.valueOf(post.getBoardId()), delta);
        }
        if (post.getUserId() != null) {
            recCacheManager.incrInterest(uid, DIM_AUTHOR, String.valueOf(post.getUserId()), delta);
        }
    }

    // ==================== 召回五路 ====================

    /**
     * 路① 关注流：读 social 的关注关系热索引（uf:following，无 feed:{uid} 收件箱）→
     * post 侧按作者集合取最新帖。
     *
     * <p>可见性口径已收口到 post 侧：{@code includeFansOnly=true} 放行"仅粉丝可见"
     * （关注关系背书，关注的人发的仅粉丝帖对我是可见的）。</p>
     *
     * @param loginId 当前登录用户内部 id
     * @param sink    召回候选集合（多路共用一个 Set，天然去重）
     */
    private void recallByFollow(Long loginId, Set<Long> sink) {
        // 契约返回的就是 List<Long>，旧代码那套 NumberUtil.isLong 过滤 + map(Long::valueOf) 已不需要
        List<Long> followeeIds = innerFollowService.listFolloweeIds(loginId);
        if (followeeIds == null || followeeIds.isEmpty()) {
            return;
        }
        sink.addAll(innerPostService.listVisiblePostIdsByAuthorIds(
                followeeIds, true, recallFollowLimit));
    }

    /**
     * 路② 板块流：social 侧的 board_follow（status=1 关注中）→ post 侧板块内公开帖，最新在前。
     * 板块帖统一只推公开（推荐流无关注关系背书，仅粉丝可见帖不进推荐）。
     */
    private void recallByBoard(Long loginId, Set<Long> sink) {
        List<Long> boardIds = innerFollowService.listFollowedBoardIds(loginId);
        if (boardIds == null || boardIds.isEmpty()) {
            return;
        }
        sink.addAll(innerPostService.listVisiblePostIdsByBoardIds(boardIds, recallBoardLimit));
    }

    /**
     * 路③ 热点：全库 score Top N（保底"全局最火"，游客/冷画像用户的主体内容源）。
     *
     * <p>这一路是<b>硬保底</b>：即使①②④⑤全空，热点路也能给出候选 → 游客看得到内容。</p>
     */
    private void recallByHot(Set<Long> sink) {
        sink.addAll(innerPostService.listHotVisiblePostIds(recallHotLimit));
    }

    /**
     * 路④ 内容标签：user_interest dim=2（标签）按权重 Top-20 → post_tag（status=1 已发布版本）反查 postId
     * → 再按可见性过滤 + 取最新 N 条。
     *
     * <p>走 post_tag 关联表而非 topic LIKE/FIND_IN_SET：能吃到 (postId,tagId,status) 联合索引，
     * 且天然只命中「已发布版本」的关联（版本化语义与审核一致）。</p>
     *
     * <p>本路是<b>两段式</b>：第一段 {@code listPostIdsByTagIds} 是 tag→postId 反查（不过滤可见性），
     * 第二段 {@code listVisiblePostIdsByIds} 才做可见性过滤与裁剪 —— 反查可能捞出私密/审核中的帖。</p>
     */
    private void recallByTag(Long loginId, Set<Long> sink) {
        List<Long> tagIds = userInterestService.lambdaQuery()
                .eq(UserInterest::getUserId, loginId)
                .eq(UserInterest::getDimension, DIM_TAG)
                .orderByDesc(UserInterest::getWeight)
                .last("limit 20")
                .list().stream()
                .map(UserInterest::getValue)
                .filter(NumberUtil::isLong)
                .map(Long::valueOf)
                .toList();
        if (tagIds.isEmpty()) {
            return;
        }
        List<Long> postIds = innerPostService.listPostIdsByTagIds(tagIds);
        if (postIds == null || postIds.isEmpty()) {
            return;
        }
        sink.addAll(innerPostService.listVisiblePostIdsByIds(postIds, recallTagLimit));
    }

    /**
     * 路⑤ 冷启动：近 {@code coldWindowHours}（默认 72h）新帖 + viewCount 低于阈值，最新在前。
     *
     * <p><b>窗口从创建时间起算、放宽到 72h</b>：先审后发下审核通过可能晚于创建 24h，
     * 24h 窗口会导致审核通过时帖子已从冷启动池消失（无互动 score=0，其他路也不命中）→ 推荐流永远看不到。</p>
     *
     * <p><b>这也是硬保护之一</b>，连同"热点保底"与"五路共用 Set 去重"，三条都是踩过坑写下的，
     * 迁移时勿删勿改。</p>
     *
     * @param sink 召回候选集合
     * @return 冷启动池的 postId（供重排注入位 5/8 使用），与并入 sink 的是同一批
     */
    private Set<Long> recallByCold(Set<Long> sink) {
        // 契约返回值即冷启动池，不再需要自己在遍历里收集；LinkedHashSet 保序（createdAt 降序）
        List<Long> coldIds = innerPostService.listColdStartPostIds(
                coldWindowHours, coldViewThreshold, recallColdLimit);
        if (coldIds == null || coldIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        sink.addAll(coldIds);
        return new LinkedHashSet<>(coldIds);
    }

    /**
     * 推荐物料可见性双保险：召回 SQL 已过滤，此处防「召回后到粗排前」帖子被下架/删除的竞态
     * （listByIds 只过滤 isDelete，status/visibility 需内存再查一次）。
     */
    private boolean isRecommendable(Post p) {
        return Objects.equals(p.getStatus(), PostStatusEnum.PUBLISHED.getCode())
                && Objects.equals(p.getAuditStatus(), PostAuditStatusEnum.APPROVED.getCode())
                && Objects.equals(p.getVisibility(), PostVisibilityEnum.PUBLIC.getCode());
    }
    /** 热度分读取：post.score（BigDecimal），null/负值兜底 0（ScoreRecalcJob 每 5 分钟重算保证有值）。 */
    private double hotScore(Post p) {
        return p.getScore() == null ? 0d : p.getScore().doubleValue();
    }

    /**
     * 分页参数兜底：默认 10，上限 20。
     */
    private int clampPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 加载长期画像
     */
    private Map<String,Double> loadProfile(Long loginId){
      return   userInterestService.lambdaQuery()
                .eq(UserInterest::getUserId,loginId)
                .list().stream()
                .collect(Collectors.toMap(
                        //主要就是取出类型和类型具体的值，权重，传化为map
                        i->i.getDimension()+":"+i.getValue(),
                        i->i.getWeight()==null?0d:i.getWeight().doubleValue(),
                        //排除脏数据
                        (a,b)->a));
    }

    /**
     * 精排公式：pScore = hotScore × (1 + α·tagAffinity + β·typeAffinity + γ·boardAffinity)。
     *
     * <p>tagAffinity：post.topic（逗号分隔 tagId 串）逐标签查 dim=2 权重取最大（无则 0）；
     * typeAffinity：post.type 查 dim=3；boardAffinity：post.boardId 查 dim=4。
     * 作者维度（dim=5）只写不读（Phase 1 不做作者亲和，防大 V 垄断精排）。</p>
     */
    private double calcPScore(Post p, Map<String, Double> profile){
        //获取到帖子的分数
        double hot = hotScore(p);
        double tagAff=0;
        if(StrUtil.isNotBlank(p.getTopic())){
            for(String tag: StrUtil.split(p.getTopic(),",")){
                Double w = profile.get(DIM_TAG + ":" + StrUtil.trim(tag));
                if(w!=null){
                    tagAff=Math.max(tagAff,w);
                }
            }
        }
        double typeAff=p.getType()==null?0d:
                profile.getOrDefault(DIM_TYPE+":"+p.getType(),0d);
        double boardAff = p.getBoardId() == null ? 0d
                : profile.getOrDefault(DIM_BOARD + ":" + p.getBoardId(), 0d);
        return hot * (1 + alphaTag * tagAff + betaType * typeAff + gammaBoard * boardAff);
    }

    /**
     * 游标定位：在 pScore 降序全量候选中找上页末条位置，返回下一位置。
     *
     * <p>score 每 5 分钟重算导致顺序微变，cursor 精确位置可能漂移：
     * 漂移区间内（±页大小）做邻近搜索兜底，找不到才回首屏。</p>
     */
    private int locateCursor(List<Scored> scored,Long cursor){
        if(cursor==null||cursor<=0){
            return 0;
        }
        //1.精确匹配（Objects.equals：Long 用 == 比较引用，雪花 id 超出缓存区必然失配）
        for (int i = 0; i < scored.size(); i++) {
            if (Objects.equals(scored.get(i).post().getId(), cursor)) {
                return i + 1;
            }
        }
        // 2. 漂移兜底：cursor 原本排在末条附近，顺序微变后改比 pScore——但 pScore <= cursor 帖原分的
        //    简化处理：直接回到首页重推（曝光去重保证不会重推已看内容，损失仅一页新鲜度）
        return 0;

    }

    /**
     * 页内重排：同作者打散（≤2 连续）→ 冷启动注入位 5/8。
     * 只调整页内顺序，不影响游标语义（§6.5）。
     * 入参为调用方已剔除曝光、顺延补齐的页内 Post 列表（直接原地重排，不复制）。
     */
    private List<Post> rerank(List<Post> list, Set<Long> coldIds){
        //同作者打散：第 3 个连续同作者帖与后方首个异作者帖交换（至多交换，不丢帖）
        for (int i = 2; i < list.size(); i++) {
            Long cur = list.get(i).getUserId();
            if (Objects.equals(cur, list.get(i - 1).getUserId())
                    && Objects.equals(cur, list.get(i - 2).getUserId())) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (!Objects.equals(list.get(j).getUserId(), cur)) {
                        Collections.swap(list, i, j);
                        break;
                    }
                }
            }
        }
        // 3. 冷启动注入：第 5、8 位插入冷启动帖（本页已有该帖则跳过顺延）。
        //    从后往前插避免前面的插入使后面的位次偏移。
        if (CollUtil.isNotEmpty(coldIds)) {
            for (int slot : new int[]{8, 5}) {
                if (slot > list.size()) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    Post p = list.get(i);
                    if (coldIds.contains(p.getId()) && i != slot - 1) {
                        list.remove(i);
                        list.add(Math.min(slot - 1, list.size()), p);
                        break;
                    }
                }
            }
        }

    return list;
    }

    /**
     * 列表装配：批量查作者（Dubbo→user）→ buildBrowseVO（同构 buildPostBrowseVO）
     * → 板块 / 标签（Dubbo→post 侧现成装配组件）→ fillLikedAndCollected（Dubbo→interaction）。
     *
     * <p><b>Phase 8 改造</b>：原先本类直接注入 {@code BoardBriefFiller} / {@code TagBriefFiller}，
     * 但这两个组件依赖 post 域的 {@code BoardService} / {@code PostTagService} / {@code TagService}，
     * 拆服务后 rec 拿不到 → 改为把装配好的 VO 列表交给 post 侧填充（一次 Dubbo 往返搞定 board + tags）。</p>
     *
     * <p>⚠️ {@code fillBoardAndTags} <b>必须接收返回值</b>：Dubbo 跨进程时入参是反序列化出来的副本，
     * post 侧的原地修改不会传回本进程；写成当 {@code void} 用会"填了个寂寞"——
     * <b>编译通过、不报错</b>，只是卡片上 board / tags 全空。</p>
     *
     * @param posts   本页帖子（已重排）
     * @param loginId 当前登录用户（游客 null，跳过 isLiked/isCollected 装配）
     */
    private List<PostBrowseVO> assemble(List<Post> posts, Long loginId) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> authorIds = posts.stream()
                .map(Post::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<User> authors = authorIds.isEmpty() ? List.of() : innerUserService.listByIds(authorIds);
        Map<Long, User> userMap = authors == null ? Map.of()
                : authors.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<PostBrowseVO> voList = posts.stream()
                .map(p -> buildBrowseVO(p, userMap))
                .collect(Collectors.toList());
        // 批量填充板块对象 + 话题标签：交给 post 侧（⚠️ 必须接收返回值，见方法注释）
        voList = innerPostService.fillBoardAndTags(voList);
        if (voList == null) {
            voList = List.of();
        }
        if (loginId != null) {
            fillLikedAndCollected(voList, loginId);
        }
        return voList;
    }

    /**
     * 实体 → 列表卡片 VO（同构 PostServiceImpl.buildPostBrowseVO：轻量字段 + 摘要 + 作者昵称头像）。
     */
    private PostBrowseVO buildBrowseVO(Post post, Map<Long, User> userMap) {
        PostBrowseVO vo = BeanUtil.copyProperties(post, PostBrowseVO.class);
        vo.setContentPreview(buildContentPreview(post.getContent()));
        User author = userMap == null ? null : userMap.get(post.getUserId());
        if (author != null) {
            vo.setUserNickname(author.getNickname());
            vo.setUserAvatar(author.getAvatar());
        }
        return vo;
    }

    /**
     * 抽取正文纯文本并截断为摘要（同构 PostServiceImpl.buildContentPreview：
     * ContentBlock JSON 数组拼 text 块；旧数据纯文本直接用；统一截断 100 字）。
     */
    private String buildContentPreview(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        String plain = content.trim();
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
                // 脏数据 fallback 纯文本截断
            }
        }
        if (StrUtil.isBlank(plain)) {
            return null;
        }
        if (plain.length() > PREVIEW_MAX_LENGTH) {
            return StrUtil.sub(plain, 0, PREVIEW_MAX_LENGTH) + "...";
        }
        return plain;
    }

    /**
     * 批量装配 isLiked / isCollected（Phase 8 改造：数据源由本地
     * {@code LikeService.batchPostLiked} / {@code CollectService.batchIsCollected}
     * 改为 Dubbo 调 interaction 的 {@code getPostLikedMap} / {@code getPostCollectedMap}）。
     *
     * <p>一次 feed 各一次 Dubbo 往返；interaction 侧走 Redis pipeline，缺失才回源 DB。</p>
     *
     * @param voList  本页卡片 VO（原地填充）
     * @param loginId 当前登录用户内部 id（非 null，调用方已判游客）
     */
    private void fillLikedAndCollected(List<PostBrowseVO> voList, Long loginId) {
        if (voList == null || voList.isEmpty()) {
            return;
        }
        List<Long> postIds = voList.stream()
                .map(PostBrowseVO::getId)
                .filter(Objects::nonNull)
                .toList();
        if (postIds.isEmpty()) {
            return;
        }
        Map<Long, Boolean> likedMap = safeMap(innerInteractionService.getPostLikedMap(loginId, postIds));
        Map<Long, Boolean> collectedMap = safeMap(innerInteractionService.getPostCollectedMap(loginId, postIds));
        voList.forEach(vo -> {
            if (vo.getId() != null) {
                vo.setIsLiked(likedMap.getOrDefault(vo.getId(), false));
                vo.setIsCollected(collectedMap.getOrDefault(vo.getId(), false));
            }
        });
    }

    /** 远端 Map 结果的空值兜底（Dubbo 异常返回 null 时避免 NPE） */
    private Map<Long, Boolean> safeMap(Map<Long, Boolean> map) {
        return map == null ? Map.of() : map;
    }
}
