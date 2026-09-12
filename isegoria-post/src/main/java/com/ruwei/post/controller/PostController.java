package com.ruwei.post.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.web.RateLimit;
import com.ruwei.model.dto.PageRequest;
import com.ruwei.model.dto.PostDTO;
import com.ruwei.model.dto.PostQueryDTO;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.model.vo.PostVO;
import com.ruwei.post.service.PostService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 帖子（内容）相关接口
 *
 * <p>审核约定：创建送审（status=3+auditStatus=1）；编辑<b>先审后发</b>（新内容直接覆盖正式字段并重新置为
 * 审核中，审核期间不对外展示，通过后恢复已发布、驳回则下架）；删除为逻辑删除（isDelete=1）。</p>
 *
 * <p><b>两个状态维度的分工</b>：{@code status}（生命周期：已发布/草稿/审核中/下架）由上述流程单向推进，
 * 作者无法手动指定；{@code visibility}（可见性：公开/仅粉丝可见/私密）才是作者可自由设置的维度。</p>
 */
@RestController
@RequestMapping("/post")
@SaCheckLogin
public class PostController {

    @Resource
    private PostService postService;

    /**
     * 创建帖子（送审：创建后 status=3 审核中 + auditStatus=1 待审，管理员审核通过后对外可见）
     *
     * <p>返回 {@link PostVO}：visibility / status / auditStatus 回显<b>中文文字</b>
     * （与入参同一套词汇），id / userId / boardId 序列化为字符串（雪花 id 防前端丢精度）。</p>
     */
    @PostMapping("/add")
    @RateLimit(limit = 5, window = 60, prefix = "post")
    public BaseResponse<PostVO> createPost(@RequestBody PostDTO postDTO) {
        PostVO postVO = postService.createPost(postDTO);
        return ResultUtils.success(postVO);
    }

    /**
     * 编辑帖子（先审后发：草稿另存为新记录直接生效；其余直接覆盖正式字段并重新送审，审核期间不对外展示）
     */
    @PostMapping("/update")
    @RateLimit(limit = 5, window = 60, prefix = "post")
    public BaseResponse<String> updatePost(@RequestBody PostDTO postDTO) {

        return postService.updatePost(postDTO);
    }

    /**
     * 设置帖子可见性（作者本人操作）
     *
     * <p>visibility：前端传<b>文字</b>（"公开" / "仅粉丝可见" / "私密"），
     * 后端经 {@code PostVisibilityEnum} 转整数（1/2/3）落库。</p>
     *
     * <p>只改可见范围，<b>不影响生命周期 status 与审核结果 auditStatus</b> ——
     * 后两者由创建送审、编辑送审、管理员审核三条流程单向推进，不开放给作者手动指定。</p>
     */
    @PostMapping("/visibility")
    public BaseResponse<String> updatePostVisibility(@RequestParam Long id, @RequestParam String visibility) {
        ThrowUtils.throwIf(id == null || StrUtil.isBlank(visibility), ErrorCode.PARAMS_ERROR, "参数不能为空");
        postService.updatePostVisibility(id, visibility);
        return ResultUtils.success("设置可见性成功");
    }

    /**
     * 置顶/取消置顶帖子（作者本人操作）。
     *
     * <p><b>约束</b>：仅「已发布」的帖子可置顶；仅作者本人可操作。
     * 置顶标记只在主页（个人/他人主页，即列表接口传 userId 查询某人帖子）体现排序，
     * 首页信息流 / 关注流不体现。</p>
     *
     * @param id    帖子内部主键
     * @param isTop true=置顶 / false=取消置顶
     */
    @PostMapping("/top")
    public BaseResponse<String> updatePostTop(@RequestParam Long id, @RequestParam Boolean isTop) {
        ThrowUtils.throwIf(id == null || isTop == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        postService.updatePostTop(id, isTop);
        return ResultUtils.success(isTop ? "置顶成功" : "已取消置顶");
    }

    /**
     * 删除帖子（逻辑删除 isDelete=1，作者或管理员）
     */
    @DeleteMapping("/{id}")
    public BaseResponse<String> deletePost(@PathVariable Long id) {
        postService.deletePost(id);
        return ResultUtils.success("删除成功");
    }

    /**
     * 查看草稿箱（当前登录用户 status=草稿 的帖子列表，按创建时间倒序）。
     *
     * <p>作者取当前登录态（不信任前端传 userId，防查他人草稿）；
     * 草稿即 status=草稿（code=2）的记录——「编辑传 status=草稿」时后端复制原帖另存，
     * 不走审核、不对外展示。审核中（code=3）的帖子属于送审流程，不算草稿。</p>
     */
    @GetMapping("/drafts")
    public BaseResponse<List<PostVO>> getDraftList() {
        return ResultUtils.success(postService.getDraftList());
    }

    /**
     * 发布草稿（草稿箱 → 发布）。
     *
     * <p>按草稿的 {@code draftOfId} 自动决定去向：编辑草稿 → 内容应用到原帖并重新送审
     * （先审后发）；新建草稿 → 创建新帖送审。成功后草稿记录被删除。发布内容以本次请求为准。</p>
     *
     * @return 目标帖子 id（字符串）：编辑草稿返回原帖 id，新建草稿返回新帖 id
     */
    @PostMapping("/publishDraft")
    @RateLimit(limit = 5, window = 60, prefix = "post")
    public BaseResponse<String> publishDraft(@RequestBody PostDTO postDTO) {
        return postService.publishDraft(postDTO.getDraftId(), postDTO);
    }

    /**
     * 删除草稿（作者本人）：逻辑删除草稿记录，并清理其图片/标签关联。
     */
    @DeleteMapping("/draft/{id}")
    public BaseResponse<String> deleteDraft(@PathVariable Long id) {
        postService.deleteDraft(id);
        return ResultUtils.success("草稿已删除");
    }

    /**
     * （点进主页后的查询 / 帖子列表页）分页查询帖子：可查自己的稿子、也可查别人的稿子。
     *
     * <p>条件：id / postCode / boardId / title / userId / createdAt（字符串字段模糊匹配、id 类字段精确匹配），
     * 均不传则查询全部；未传排序字段时默认按创建时间倒序（最新在前）。</p>
     *
     * <p><b>可见性</b>：仅当 userId 条件等于当前登录用户（查自己）时放行全部状态（草稿/审核中/下架可见）；
     * 查询全部或他人帖子时只返回「已发布」，草稿/审核中/下架不可见。</p>
     *
     * <p><b>返回列表专用 VO（{@link PostBrowseVO}）</b>：仅含卡片渲染所需轻量字段
     * （标题 / 封面 / 作者昵称头像 / 计数 / 时间），不含正文与图片全列表 ——
     * 用户点击进入帖子后，由 {@code GET /post/{id}} 详情接口返回完整 {@link PostVO}。</p>
     */
    @PostMapping("/list")
    @SaIgnore
    public BaseResponse<IPage<PostBrowseVO>> listPosts(@RequestBody PostQueryDTO postQueryDTO) {
        return ResultUtils.success(postService.listPosts(postQueryDTO));
    }

    /**
     * 帖子详情（点进帖子后展示完整内容）。
     *
     * <p>返回完整 {@link PostVO}：正文 content、图片全列表 imageUrl、话题 topic、
     * 可见性/状态/审核结果回显文字，雪花 id 序列化为字符串防前端丢精度。</p>
     *
     * <p><b>可见性</b>：与 {@code /list} 规则一致 —— 作者本人可看自己任意状态
     * （草稿/审核中/下架）；非作者仅可看「已发布」，其余状态一律返回「帖子不存在或未发布」，
     * 不泄露内容存在性。</p>
     */
    @GetMapping("/{id}")
    @SaIgnore
    public BaseResponse<PostVO> getPostDetail(@PathVariable Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        return ResultUtils.success(postService.getPostDetail(id));
    }

    /**
     * 关注流：我关注的人的帖子列表（需登录，什么都不用传，内部组装）。
     *
     * <p>先查我关注的人（user_follow 关注中），再查这些人的帖子，过滤：
     * <b>已发布 + 审核通过 + 可见性∈{公开, 仅粉丝可见}</b>，按创建时间倒序。
     * 返回列表专用 {@link PostBrowseVO}（标题/封面/作者昵称头像/计数/时间）。</p>
     *
     */
    @PostMapping("/followFist")
    public BaseResponse<IPage<PostBrowseVO>> listFollowPosts(@RequestBody PageRequest pageRequest) {
        return ResultUtils.success(postService.listFollowPosts(pageRequest.getCurrent(), pageRequest.getPageSize()));
    }

    /**
     * 浏览埋点：详情页打开时调用一次（仅登录用户生效，游客静默忽略）。
     *
     * <p>{@code @SaIgnore} 与 {@code GET /post/{id}} 口径一致——游客可浏览公开帖，但<b>不参与</b>
     * 浏览统计（viewCount 不累加、浏览历史不写入），由 {@code PostServiceImpl.recordView} 内部
     * 对未登录直接返回。限流对齐发帖口径，防刷量。</p>
     */
    @PostMapping("/{id}/view")
    @SaIgnore
    @RateLimit(limit = 10, window = 60, prefix = "view")
    public BaseResponse<String> recordView(@PathVariable Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        postService.recordView(id);
        return ResultUtils.success("ok");
    }

    /**
     * 我的浏览历史（需登录，本人视角）：按<b>最近一次浏览时间倒序</b>分页返回。
     *
     * <p>返回列表专用 {@link PostBrowseVO}（去重后每帖一条，重复浏览只刷新排序位置，
     * 不产生重复卡片）。</p>
     */
    @PostMapping("/viewedList")
    public BaseResponse<IPage<PostBrowseVO>> listViewedPosts(@RequestBody PageRequest pageRequest) {
        return ResultUtils.success(postService.listViewedPosts(pageRequest.getCurrent(), pageRequest.getPageSize()));
    }

    /**
     * 删除单条浏览记录（需登录，仅能删自己的）：按 ukUserPost(userId, postId) 物理删除一行。
     */
    @PostMapping("/viewed/remove")
    @RateLimit(limit = 10, window = 60, prefix = "view")
    public BaseResponse<String> removeViewed(@RequestParam Long postId) {
        ThrowUtils.throwIf(postId == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        postService.removeViewed(postId);
        return ResultUtils.success("ok");
    }

    /**
     * 清空本人全部浏览历史（需登录，只删当前用户的行）。
     */
    @PostMapping("/viewed/clear")
    @RateLimit(limit = 5, window = 60, prefix = "view")
    public BaseResponse<String> clearViewed() {
        postService.clearViewed();
        return ResultUtils.success("ok");
    }

    /**
     * 站外分享埋点：详情页分享成功后回调（channel 渠道可选，0未知 1微信 2朋友圈 3QQ 4微博 5复制链接）。
     */
    @PostMapping("/{id}/share")
    @RateLimit(limit = 10, window = 60, prefix = "share")
    public BaseResponse<String> recordShare(@PathVariable Long id, @RequestParam(required = false) Integer channel) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        postService.recordShare(id, channel);
        return ResultUtils.success("ok");
    }

    /**
     * 站内分享给指定用户（需登录）：shareCount+1 + 写流水 + 通知接收者。
     */
    @PostMapping("/{id}/shareTo")
    @RateLimit(limit = 10, window = 60, prefix = "share")
    public BaseResponse<String> recordShareTo(@PathVariable Long id, @RequestParam Long targetUserId) {
        ThrowUtils.throwIf(id == null || targetUserId == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        postService.recordShareTo(id, targetUserId);
        return ResultUtils.success("ok");
    }


}
