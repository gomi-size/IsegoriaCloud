package com.ruwei.post.service;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.model.dto.AdminPostStatusDTO;
import com.ruwei.model.dto.PostDTO;
import com.ruwei.model.dto.PostQueryDTO;
import com.ruwei.model.entity.Post;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.model.vo.PostVO;

import java.util.List;

/**
* @author Administrator
* @description 针对表【post(帖子/笔记表(推荐系统物料主表))】的数据库操作Service
* @createDate 2026-08-05 10:16:16
*
* <p><b>审核状态机约定（2026-08-05）：</b>
* <ul>
*   <li>{@code status}（生命周期）：1已发布 2草稿 3审核中 4下架（5删除废弃，删除统一走 {@code isDelete} 逻辑删除）；</li>
*   <li>{@code auditStatus}（审核结果）：1待审 2通过 3驳回；</li>
*   <li><b>创建送审</b>：createPost 后 status=3 + auditStatus=1，管理员审核通过才对外可见；</li>
*   <li><b>编辑先审后发</b>：updatePost 直接把新内容写入正式字段，并置 status=3+auditStatus=1，
*       审核期间帖子不对外展示，通过后恢复已发布、驳回则下架；</li>
*   <li><b>作者只能改可见性</b>：updatePostVisibility 设置 公开/仅粉丝可见/私密；
*       status 属于平台侧的生命周期，由创建/编辑/审核流程单向推进，不开放给作者手动指定；</li>
*   <li><b>审核只推进状态</b>：待审内容在创建/编辑时已写入正式字段，auditPost 不搬运内容，
*       仅把 status 推进到 已发布/下架，并把 status=审核中 的图片、标签迁移为最终状态。</li>
* </ul></p>
*/
public interface PostService extends IService<Post> {

    /**
     * 创建帖子（送审：status=3 审核中 + auditStatus=1 待审，审核通过后对外可见）。
     * 作者取当前登录态；内容过敏感词 filter；写 post + post_image + tag/post_tag；板块帖子数 +1。
     *
     * @param dto 创建入参（title/content 必填，images/tags 可选）
     * @return 创建后的帖子 {@link PostVO}（含对外编码 postCode；visibility/status/auditStatus
     *         回显<b>中文文字</b>与入参同一套词汇，雪花 id 序列化为字符串防前端丢精度，
     *         imageUrl 按 sort 回读、topic 解析为 TagVO 列表）
     */
    PostVO createPost(PostDTO dto);

    /**
     * 编辑帖子（先审后发）：
     * <ul>
     *   <li>草稿（status=2）：另存为一条新的草稿记录，不走审核；</li>
     *   <li>已发布/审核中/下架：<b>直接修改正式字段</b>（title/content/cover/topic/visibility，
     *       仅覆盖本次传入的字段），图片与标签全量替换，并置 status=3 审核中 + auditStatus=1 待审。
     *       审核期间帖子不对外展示，由管理员通过后恢复已发布、驳回则下架。</li>
     * </ul>
     * 仅作者本人可编辑。
     *
     * @param dto 编辑入参（id 必传）
     */
    BaseResponse<String> updatePost(PostDTO dto);

    /**
     * 设置帖子可见性（作者本人操作）。
     * 前端传<b>文字</b>（"公开"/"仅粉丝可见"/"私密"），后端经 {@code PostVisibilityEnum} 转整数（1/2/3）落库。
     *
     * <p><b>只改 visibility，不触碰 status / auditStatus</b>：
     * 生命周期（已发布/草稿/审核中/下架）由创建、编辑、审核三条流程单向推进，作者不能手动指定，
     * 否则可以把驳回的帖子直接改回「已发布」绕过审核。作者能自由支配的只有可见范围这一维度。</p>
     *
     * <p>幂等：目标可见性与当前一致时直接返回成功。可见性与审核状态正交，审核中同样可以调整。</p>
     *
     * @param id             帖子内部主键
     * @param visibilityText 目标可见性文字（"公开"/"仅粉丝可见"/"私密"）
     */
    void updatePostVisibility(Long id, String visibilityText);

    /**
     * 置顶/取消置顶帖子（作者本人操作）。
     *
     * <p><b>约束</b>：仅「已发布」（status=1）的帖子可置顶——草稿/审核中/下架本就不对外展示，
     * 置顶无意义；仅作者本人可操作。置顶标记<b>只在主页场景</b>（按 userId 查询自己/他人帖子列表）
     * 体现排序，首页信息流 / 关注流不体现。</p>
     *
     * <p>幂等：目标置顶状态与当前一致时直接返回成功（避免 update 影响 0 行被误判失败）。</p>
     *
     * @param id    帖子内部主键
     * @param isTop true=置顶 / false=取消置顶
     */
    void updatePostTop(Long id, Boolean isTop);

    /**
     * 设置/取消精华（管理员操作）。
     *
     * <p><b>约束</b>：仅「已发布」（status=1）的帖子可设精华，草稿/审核中/下架不对外展示，
     * 设精华无意义（与置顶口径一致）。管理员权限由 Controller 层 {@code @SaCheckRole("admin")} 保证。</p>
     *
     * <p>幂等：目标精华状态与当前一致时直接返回成功。</p>
     *
     * @param id        帖子内部主键
     * @param isEssence true=设为精华 / false=取消精华
     */
    void updatePostEssence(Long id, Boolean isEssence);

    /**
     * 删除帖子（逻辑删除 isDelete=1，作者或管理员）：
     * 清理 post_image / post_tag 关联（物理删），关联标签 useCount 回退，板块帖子数 -1。
     *
     * @param id 帖子内部主键
     */
    void deletePost(Long id);

    /**
     * 管理员审核帖子（仅推进状态，不搬运内容）。
     *
     * <p>待审内容在 createPost / updatePost 时已直接写入正式字段，审核只做两件事：
     * 推进 status/auditStatus，并把 status=3 审核中 的图片/标签迁移为最终状态
     * （同时清理该帖其它历史版本、回退对应标签 useCount）。</p>
     *
     * <p><b>前置条件</b>：帖子必须处于 status=3 审核中，否则抛操作异常（防重复审核 / 误操作）。
     * 板块帖子数已在创建时计入，审核环节不累加。</p>
     *
     * @param id   帖子内部主键
     * @param pass true=通过（status=1 已发布 + auditStatus=2 通过）；
     *             false=驳回（status=4 下架 + auditStatus=3 驳回；正式字段已是新内容、无旧版可回退，
     *             故不能对外展示，作者修改后可重新提交审核）
     */
    void auditPost(Long id, Boolean pass,String message);

    /**
     * 查看草稿箱：当前登录用户 status=草稿 的帖子列表（按创建时间倒序）。
     *
     * <p>草稿即 status=草稿）的记录——
     * 「编辑传 status=草稿」时后端复制原帖另存为新记录，不对外展示、不走审核；
     * status=审核中（code=3）是送审流程中的帖子，不属于草稿。</p>
     *
     * <p>作者取当前登录态（不信任前端传 userId，防查他人草稿）；图片按草稿版本回读。</p>
     *
     * @return 草稿帖子列表（PostVO，枚举回文字、雪花 id 转字符串、imageUrl 按草稿版本回读）
     */
    List<PostVO> getDraftList();

    /**
     * 帖子分页查询（列表页）：可查自己的帖子、也可查别人的帖子。
     *
     * <p>条件：id / postCode / boardId / title / userId / createdAt（字符串字段模糊、id 类精确），
     * 均不传则查询全部；未传排序字段时默认按创建时间倒序（最新在前）。</p>
     *
     * <p><b>可见性</b>：仅当 {@code userId} 条件等于当前登录用户（查自己）时放行全部状态
     * （草稿/审核中/下架可见）；查询全部或他人帖子时只返回「已发布」（status=1）。</p>
     *
     * <p><b>返回列表专用 VO（{@link PostBrowseVO}）</b>：仅含卡片渲染所需轻量字段
     * （标题/封面/作者昵称头像/计数/时间等），<b>不含正文与图片全列表</b>；
     * 作者信息由本方法批量查 user 表装配，避免逐条查询造成 N+1。</p>
     *
     * @param postQueryDTO 查询条件（含分页/排序参数）
     * @return 帖子分页结果（PostBrowseVO，列表轻量展示；点击进入详情请用 {@link #getPostDetail(Long)}）
     */
    IPage<PostBrowseVO> listPosts(PostQueryDTO postQueryDTO);

    /**
     * 帖子详情查询（点进帖子后展示完整内容）。
     *
     * <p><b>可见性</b>：与 {@link #listPosts(PostQueryDTO)} 的列表规则保持一致 ——
     * 作者本人可查看自己任意状态（草稿/审核中/下架）的帖子；非作者只能查看「已发布」（status=1）的帖子，
     * 其余状态一律视为「帖子不存在」，不泄露内容存在性。</p>
     *
     * @param id 帖子内部主键
     * @return 帖子完整详情 {@link PostVO}（正文 content、图片全列表 imageUrl、话题 topic、
     *         可见性/状态/审核结果回显文字，雪花 id 序列化为字符串）
     */
    PostVO getPostDetail(Long id);

    /**
     * 关注流：我关注的人的帖子列表（需登录）。
     *
     * <p><b>两步查询</b>：① 先查我关注的人 —— {@code user_follow} 表中
     * {@code followerId = 当前登录用户内部 id} 且 {@code status = 1}（关注中），取被关注者内部 id 列表；
     * ② 再查这些人的帖子，过滤条件：<b>已发布（status=1）+ 审核通过（auditStatus=2）
     * + 可见性∈{公开, 仅粉丝可见}（visibility∈{1,2}）</b>，按创建时间倒序（最新在前）。</p>
     *
     * <p><b>粉丝可见语义说明</b>：严格语义下「仅粉丝可见」帖子应只有作者粉丝（互关）可见，
     * 当前按入参约定直接放行 {@code FANS_ONLY}（列表源本身是我关注的人）；若需互关校验可在后续迭代补充。</p>
     *
     * <p>返回列表专用 VO（{@link PostBrowseVO}），作者信息批量装配避免 N+1。</p>
     *
     * @param current  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 关注流分页结果（PostBrowseVO）
     */
    IPage<PostBrowseVO> listFollowPosts(long current, long pageSize);

    /**
     * 管理员帖子列表（统一入口：已发布/草稿/审核中/下架 全状态可查）。
     *
     * <p>条件与 {@link #listPosts(PostQueryDTO)} 一致：id / postCode / boardId / title / userId / createdAt
     * （字符串字段模糊、id 类精确），未传排序字段时默认按创建时间倒序。
     * <b>status 由 DTO 传入（中文文字→枚举码精确匹配），为空则查询全部状态</b>；
     * 管理端不做可见性过滤（要看的就是包括未发布在内的全部内容）。</p>
     *
     * @param postQueryDTO 查询条件（含分页/排序参数）
     * @return 全状态帖子的分页结果（PostVO，图片按帖子当前状态版本回读）
     */
    IPage<PostVO> listAdminPosts(PostQueryDTO postQueryDTO);

    /**
     * 管理员自由设置帖子状态（status / visibility，传哪个改哪个，至少传一个）。
     *
     * <p>status 传枚举码（1已发布 2草稿 3审核中 4下架），visibility 传枚举码（1公开 2仅粉丝可见 3私密）。
     * <b>status 变化时自动联动</b>（保持与审核流口径一致）：</p>
     * <ul>
     *   <li>user.postCount / board.postCount 按「仅已发布」口径增减（非发布→已发布 +1，已发布→非发布 -1）；</li>
     *   <li>auditStatus 同步映射：已发布→通过、下架→驳回、草稿/审核中→待审；</li>
     *   <li>图片/标签版本归一到新状态：保留变更前状态版本的关联（当前内容），清理其余历史版本（标签回退 useCount）。</li>
     * </ul>
     *
     * @param dto 帖子 id + 待设置的状态/可见性（至少一个非空）
     */
    void adminSetPostStatus(AdminPostStatusDTO dto);

    /**
     * 发布草稿（草稿箱 → 发布）。按草稿的 {@code draftOfId} 决定去向：
     * <ul>
     *   <li><b>draftOfId 非空</b>（编辑草稿）：把本次传入内容应用到原帖并重新送审（先审后发）；</li>
     *   <li><b>draftOfId 为空</b>（新建草稿）：用本次传入内容创建新帖送审。</li>
     * </ul>
     * 成功后删除草稿记录（含图片/标签关联清理）。发布内容以本次请求为准（草稿槽位可能滞后）。
     *
     * @param draftId 草稿记录 id
     * @param dto     发布内容（status 强制走送审，作者不能借草稿绕过审核）
     * @return 目标帖子 id（字符串）：编辑草稿返回原帖 id，新建草稿返回新帖 id
     */
    BaseResponse<String> publishDraft(Long draftId, PostDTO dto);

    /**
     * 删除草稿（作者本人）：逻辑删除草稿记录，并清理其图片/标签关联（标签 useCount 回退）。
     * 与 deletePost 的区别：草稿未参与板块/作者计数，不做计数回退。
     *
     * @param id 草稿记录内部主键
     */
    void deleteDraft(Long id);

    /**
     * 浏览埋点：详情页打开时调用一次（仅登录用户生效）。
     *
     * <p><b>游客不参与</b>：未登录直接忽略（不累加 viewCount、不写浏览历史），
     * 避免「打开 API 文档/爬虫/游客逛帖」污染统计。登录用户：viewCount 原子 +1
     * （{@code CountUtils.increment}），并 upsert 写浏览历史（去重累计，重复浏览只刷新 lastViewAt）。</p>
     *
     * @param id 帖子内部主键
     */
    void recordView(Long id);

    /**
     * 我的浏览历史（需登录，本人视角）：按<b>最近一次浏览时间倒序</b>分页返回。
     *
     * <p>先分页查 {@code view_history}（orderBy lastViewAt DESC），再批量查帖，
     * 过滤已删除/非已发布；因 IN 查询结果无序，按 view_history 原始顺序重排后装配
     * {@link PostBrowseVO}（作者信息批量查询避免 N+1，复用列表装配逻辑）。</p>
     *
     * <p><b>total 语义</b>：保持 view_history 总条数（含已被删/下架帖的占位计数），
     * 帖子被作者删除/下架后不再展示，实际返回可能略少于 pageSize。</p>
     *
     * @param current  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 浏览历史分页结果（PostBrowseVO）
     */
    IPage<PostBrowseVO> listViewedPosts(long current, long pageSize);

    /**
     * 删除单条浏览记录（需登录，仅能删自己的）。
     *
     * <p>按唯一键 ukUserPost(userId, postId) 物理删除一行；带 userId 条件防越权删他人记录。</p>
     *
     * @param postId 帖子内部主键
     */
    void removeViewed(Long postId);

    /**
     * 清空本人全部浏览历史（需登录）。
     *
     * <p>高危操作：只删当前登录用户的行（ViewHistoryService.clearAll 内部强制带 userId 条件）。</p>
     */
    void clearViewed();

    /**
     * 我的收藏列表（需登录，本人视角）：按<b>收藏时间倒序</b>分页返回。
     *
     * <p>先分页查 {@code post_collect}（orderBy createdAt DESC，folderId=0 默认收藏夹），
     * 再批量查帖、过滤已删除/非已发布，按收藏顺序重排后复用列表装配
     * （{@code buildPostBrowseVO} + {@code fillIsLiked} + {@code fillIsCollected}）。
     * 与 {@link #listViewedPosts(long, long)} 同构。</p>
     *
     * @param current  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 我的收藏分页结果（PostBrowseVO）
     */
    IPage<PostBrowseVO> listMyCollect(long current, long pageSize);


    /**
     * 站外分享埋点：shareCount 原子 +1，并写分享流水（channel 渠道，无接收者，不通知）。
     *
     * <p>分享是离散动作（可重复），不建关系表、无取消动作；前端在分享成功回调（或点击分享按钮）时调用。</p>
     *
     * @param id      帖子内部主键
     * @param channel 站外渠道（0未知 1微信 2朋友圈 3QQ 4微博 5复制链接），可空（缺省 0）
     */
    void recordShare(Long id, Integer channel);

    /**
     * 站内分享给指定用户：shareCount 原子 +1、写分享流水（targetUserId），
     * 事务提交后发 {@code evt.share} 消息通知接收者（type=8，按天幂等）。
     *
     * @param id           帖子内部主键
     * @param targetUserId 接收者内部 id（校验存在且非自己）
     */
    void recordShareTo(Long id, Long targetUserId);
}
