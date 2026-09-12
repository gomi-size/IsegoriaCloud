package com.ruwei.post.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ruwei.common.web.RateLimit;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.model.dto.BoardQueryDTO;
import com.ruwei.model.dto.BoardUpdateDTO;
import com.ruwei.model.dto.CreateBoardDTO;
import com.ruwei.model.entity.Board;
import com.ruwei.post.service.BoardService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 贴吧板块
 */
@RestController
@RequestMapping("/boards")
public class BoardController {

    @Resource
    private BoardService boardService;

    /**
     * 创建板块（创建者为吧主）
     */
    @PostMapping("/add")
    @SaCheckLogin
    @RateLimit(limit = 2, window = 60, prefix = "board")
    public BaseResponse<Board> createBoard(@RequestBody CreateBoardDTO createBoardDTO) {
        Board board = boardService.createBoard(createBoardDTO);
        return ResultUtils.success(board);
    }

    /**
     * 编辑板块信息（吧主/管理员）
     */
    @PostMapping("/update")
    @SaCheckLogin
    public BaseResponse<String> updateBoard(@RequestBody BoardUpdateDTO boardUpdateDTO) {
        boardService.updateBoard(boardUpdateDTO);
        return ResultUtils.success("修改成功");
    }

    /**
     * 分页获取板块列表（支持按名称模糊搜索，按关注数倒序）
     * 入参 BoardQueryDTO
     *
     * <p>列表查询<b>公开</b>（未登录也可浏览首页板块导航）：用 {@code @SaIgnore} 覆盖类级
     * {@code @SaCheckLogin}。创建/编辑/删除等写操作仍受类级登录校验保护。</p>
     */
    @SaIgnore
    @PostMapping("/list")
    public BaseResponse<IPage<Board>> listBoards(@RequestBody BoardQueryDTO queryDTO) {
        return ResultUtils.success(boardService.listBoards(queryDTO));
    }

    /**
     * 吧主删除自己创建的板块（逻辑删除：isDelete=1，数据保留可恢复）
     */
    @DeleteMapping("/{id}")
    @SaCheckLogin
    public BaseResponse<String> deleteBoardByCreator(@PathVariable Long id) {
        boardService.deleteBoardByCreator(id);
        return ResultUtils.success("删除成功");
    }

    /**
     * 管理员封禁板块（status=2，可恢复，不触发逻辑删除）
     */
    @DeleteMapping("/{id}/ban")
    @SaCheckRole("admin")
    public BaseResponse<String> banBoardByAdmin(@PathVariable Long id) {
        boardService.banBoardByAdmin(id);
        return ResultUtils.success("封禁成功");
    }

    /**
     * 管理员彻底删除板块（物理删除：数据不可恢复）
     */
    @DeleteMapping("/{id}/admin")
    @SaCheckRole("admin")
    public BaseResponse<String> deleteBoardByAdmin(@PathVariable Long id) {
        boardService.deleteBoardByAdmin(id);
        return ResultUtils.success("删除成功");
    }



}
