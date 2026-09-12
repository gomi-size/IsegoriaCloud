package com.ruwei.post.controller.manager;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.model.dto.AdminPostStatusDTO;
import com.ruwei.model.dto.PostQueryDTO;
import com.ruwei.model.vo.PostVO;
import com.ruwei.post.service.PostService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 帖子（内容）相关接口
 *
 * <p>审核约定：创建送审（status=3+auditStatus=1）；编辑<b>先审后发</b>（新内容直接覆盖正式字段并重新置为
 * 审核中，审核期间不对外展示）；审核只推进状态，不搬运内容。</p>
 */
@RestController
@RequestMapping("/adminPost")
@SaCheckLogin
@SaCheckRole("admin")
public class PostManagerController {

    @Resource
    private PostService postService;

    /**
     * 管理员审核帖子（仅对 status=3 审核中 的帖子有效）
     * 对没有通过的稿子需要说明未通过的消息
     * pass：true 通过 → 已发布 / false 驳回 → 下架
     */
    @PostMapping("/audit")
    public BaseResponse<String> auditPost(@RequestParam Long postId, @RequestParam Boolean pass, @RequestParam String message) {
        postService.auditPost(postId, pass,message);
        return ResultUtils.success(pass ? "审核通过" : "已驳回");
    }

    /**
     * 设置/取消帖子精华（管理员操作）。
     *
     * <p><b>约束</b>：仅「已发布」的帖子可设为精华，草稿/审核中/下架不对外展示，设精华无意义。</p>
     *
     * @param postId    帖子内部主键
     * @param isEssence true=设为精华 / false=取消精华
     */
    @PostMapping("/essence")
    public BaseResponse<String> updatePostEssence(@RequestParam Long postId, @RequestParam Boolean isEssence) {
        ThrowUtils.throwIf(postId == null || isEssence == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        postService.updatePostEssence(postId, isEssence);
        return ResultUtils.success(isEssence ? "已设为精华" : "已取消精华");
    }

    /**
     * 管理员帖子列表（全状态可查：已发布/草稿/审核中/下架），分页查询。
     *
     * <p>条件与用户列表一致：id / postCode / boardId / title / userId / createdAt
     * （字符串字段模糊匹配、id 类字段精确匹配）；
     * <b>status 传中文文字（已发布/草稿/审核中/下架）按状态筛选，不传则查询全部状态</b>；
     * 未传排序字段时默认按创建时间倒序（最新在前）。</p>
     */
    @PostMapping("/list")
    public BaseResponse<IPage<PostVO>> listAdminPosts(@RequestBody PostQueryDTO postQueryDTO) {
        return ResultUtils.success(postService.listAdminPosts(postQueryDTO));
    }

    /**
     * 管理员自由设置帖子状态（status / visibility，传哪个改哪个，至少传一个）。
     *
     * <p>status 传枚举码：1已发布 2草稿 3审核中 4下架；visibility 传枚举码：1公开 2仅粉丝可见 3私密。
     * status 变化时自动联动：user/board 的 postCount 按「仅已发布」口径增减、
     * auditStatus 同步映射（已发布→通过、下架→驳回、其余→待审）、图片/标签版本归一到新状态。</p>
     */
    @PostMapping("/setStatus")
    public BaseResponse<String> setPostStatus(@RequestBody AdminPostStatusDTO dto) {
        postService.adminSetPostStatus(dto);
        return ResultUtils.success("设置成功");
    }



}
