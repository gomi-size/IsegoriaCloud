package com.ruwei.interaction.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.innerservice.InnerPostService;
import com.ruwei.interaction.service.CollectService;
import com.ruwei.model.dto.PageRequest;
import com.ruwei.model.entity.Post;
import com.ruwei.model.entity.PostCollect;
import com.ruwei.model.vo.CollectToggleVO;
import com.ruwei.model.vo.PostBrowseVO;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 帖子收藏接口（需登录，物理删 toggle，DB 直写）。
 *
 * <p>收藏为低频私密行为，不走点赞的 Redis 先行 + MQ 落库；收藏不通知作者。
 * {@code folderId} 预留收藏夹（Phase 1 未分组，代码写死 0=默认收藏夹）。</p>
 */
@RestController
@RequestMapping("/collect")
@SaCheckLogin
public class CollectController {

    @Resource
    private CollectService collectService;

    @DubboReference
    private InnerPostService innerPostService;

    /**
     * 收藏/取消收藏 toggle（无状态翻转），返回 {isCollected, collectCount}。
     */
    @PostMapping("/{postId}")
    public BaseResponse<CollectToggleVO> toggle(@PathVariable Long postId) {
        ThrowUtils.throwIf(postId == null, ErrorCode.PARAMS_ERROR, "帖子id不能为空");
        return ResultUtils.success(collectService.toggle(postId));
    }

    /**
     * 我的收藏列表（按收藏时间倒序分页，返回列表专用 PostBrowseVO）。
     */
    @PostMapping("/list")
    public BaseResponse<IPage<PostBrowseVO>> listMyCollect(@RequestBody PageRequest pageRequest) {
        long loginId = StpUtil.getLoginIdAsLong();
        long current = pageRequest.getCurrent();
        long pageSize = pageRequest.getPageSize();

        IPage<PostCollect> relationPage = collectService.pageMyCollect(loginId, current, pageSize);
        long total = collectService.countMyCollect(loginId);
        List<Long> postIds = relationPage.getRecords().stream()
                .map(PostCollect::getPostId)
                .toList();

        Page<PostBrowseVO> result = new Page<>(current, pageSize, total);
        result.setRecords(postIds.isEmpty()
                ? List.of()
                : innerPostService.buildBrowseVOList(loginId, postIds));
        return ResultUtils.success(result);
    }

    /**
     * 查询当前用户对某帖子的收藏状态 + 最新收藏数（详情页渲染初始状态）。
     */
    @GetMapping("/{postId}/status")
    public BaseResponse<CollectToggleVO> status(@PathVariable Long postId) {
        ThrowUtils.throwIf(postId == null, ErrorCode.PARAMS_ERROR, "帖子id不能为空");
        Long loginId = StpUtil.getLoginIdAsLong();
        Post post = innerPostService.getById(postId);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        boolean collected = collectService.batchIsCollected(List.of(postId), loginId)
                .getOrDefault(postId, false);
        return ResultUtils.success(new CollectToggleVO(collected, post.getCollectCount()));
    }
}
