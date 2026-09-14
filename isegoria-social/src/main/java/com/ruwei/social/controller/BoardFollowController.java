package com.ruwei.social.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;

import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.web.RateLimit;
import com.ruwei.model.dto.BoardFollowPageDTO;
import com.ruwei.model.entity.Board;
import com.ruwei.model.vo.UserVO;
import com.ruwei.social.service.BoardFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 板块关注关系的控制类
 *
 * <p>关注者一律取自当前登录态（内部 id），入参仅需板块内部主键 boardId。
 * 与 {@link UserFollowController}（用户关注）结构对齐。</p>
 */
@SaCheckLogin
@RestController
@RequestMapping("/boardFollow")
public class BoardFollowController {

    @Resource
    private BoardFollowService boardFollowService;

    /**
     * 关注板块
     * 入参 boardId（板块内部主键）
     */
    @PostMapping("/follow")
    @RateLimit(limit = 10, window = 1, prefix = "boardFollow")
    public BaseResponse<String> followBoard(Long boardId) {
        boardFollowService.followBoard(boardId);
        return ResultUtils.success("关注成功");
    }

    /**
     * 取消关注板块
     * 入参 boardId（板块内部主键）
     */
    @PostMapping("/cancelFollow")
    @RateLimit(limit = 10, window = 1, prefix = "boardFollow")
    public BaseResponse<String> cancelFollowBoard(Long boardId) {
        boardFollowService.cancelFollowBoard(boardId);
        return ResultUtils.success("取消成功");
    }

    /**
     * 当前登录用户是否已关注该板块
     * 入参 boardId（板块内部主键）
     */
    @PostMapping("/isFollowed")
    public BaseResponse<Boolean> isFollowed(Long boardId) {
        return ResultUtils.success(boardFollowService.isFollowed(boardId));
    }

    /**
     * 当前登录用户关注的板块列表（分页，按关注时间倒序）
     */
    @PostMapping("/getFollowBoardList")
    public BaseResponse<IPage<Board>> getFollowBoardList(@RequestBody BoardFollowPageDTO boardFollowPageDTO) {
        return ResultUtils.success(boardFollowService.getFollowBoardList(boardFollowPageDTO));
    }

    /**
     * 当前登录用户创建的板块的粉丝列表（分页，按关注时间倒序）
     */
    @PostMapping("/getFansBoardList")
    public BaseResponse<IPage<UserVO>> getFansBoardList(@RequestBody BoardFollowPageDTO boardFollowPageDTO) {
        return ResultUtils.success(boardFollowService.getFansBoardList(boardFollowPageDTO));
    }
}
