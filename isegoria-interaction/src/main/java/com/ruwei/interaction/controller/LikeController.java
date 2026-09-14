package com.ruwei.interaction.controller;

import cn.hutool.core.util.StrUtil;

import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.interaction.service.LikeService;
import com.ruwei.model.vo.LikeToggleVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/postLike")
@RestController
public class LikeController {



    @Resource
    private LikeService likeService;

    /** 帖子点赞 toggle（无状态翻转，返回新状态） */
    @PostMapping("/post/{postCode}")
    //@RateLimit(limit = 10, window = 1, prefix = "like")
    public BaseResponse<LikeToggleVO> togglePost(@PathVariable("postCode") String postCode) {
        ThrowUtils.throwIf(StrUtil.isBlank(postCode), ErrorCode.PARAMS_ERROR, "帖子编码不能为空");
        return ResultUtils.success(likeService.togglePostLike(postCode));
    }

    /** 评论点赞 toggle */
    @PostMapping("/comment/{commentId}")
    //@RateLimit(limit = 10, window = 1, prefix = "like")
    public BaseResponse<LikeToggleVO> toggleComment(@PathVariable("commentId") Long commentId) {
        ThrowUtils.throwIf(commentId == null, ErrorCode.PARAMS_ERROR, "评论id不能为空");
        return ResultUtils.success(likeService.toggleCommentLike(commentId));
    }

    /** 我的点赞状态 + 当前计数 */
    @GetMapping("/post/{postCode}/status")
    public BaseResponse<LikeToggleVO> postStatus(@PathVariable("postCode") String postCode) {
        ThrowUtils.throwIf(StrUtil.isBlank(postCode), ErrorCode.PARAMS_ERROR, "帖子编码不能为空");
        return ResultUtils.success(likeService.getPostLikeStatus(postCode));
    }

    /** 帖子点赞总数（Redis 优先，缺失回源 DB） */
    @GetMapping("/post/{postCode}/count")
    public BaseResponse<Long> postCount(@PathVariable("postCode") String postCode) {
        ThrowUtils.throwIf(StrUtil.isBlank(postCode), ErrorCode.PARAMS_ERROR, "帖子编码不能为空");
        return ResultUtils.success(likeService.getPostLikeCount(postCode));
    }
}
