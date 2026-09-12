package com.ruwei.post.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.web.RateLimit;
import com.ruwei.model.dto.CommentAddDTO;
import com.ruwei.model.dto.CommentPageDTO;
import com.ruwei.model.dto.CommentReplyPageDTO;
import com.ruwei.model.vo.CommentVO;
import com.ruwei.post.service.CommentService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/comment")
@SaCheckLogin
public class CommentController {

    @Resource
    private CommentService commentService;


    /**
     * 评论（帖子评论）
     */
    @PostMapping("/postComment")
    @RateLimit(limit = 10, window = 60, prefix = "comment")
    public BaseResponse<String> addComment(@RequestBody CommentAddDTO commentAddDTO){

        ThrowUtils.throwIf(BeanUtil.isEmpty(commentAddDTO)
                || StrUtil.isBlank(commentAddDTO.getPostCode())
                || StrUtil.isBlank(commentAddDTO.getContent()),
                ErrorCode.PARAMS_ERROR,"帖子id或者内容为空");
        commentService.addComment(commentAddDTO);

        return ResultUtils.success("评论成功");
    }

    /**
     * 删除评论
     */
    @PostMapping("/delete")
    @RateLimit(limit = 30, window = 60, prefix = "commentDelete")
    public BaseResponse<String> deleteComment(Long commentId){
        commentService.deleteComment(commentId);


        return ResultUtils.success("删除成功");

    }

    /**
     * 帖子评论列表（一级评论分页 + 每条挂前 2 条二级回复，防 N+1，对齐文档 §6.4）。
     *
     * <p>盖楼语义固定按创建时间正序；一级仅展示 status=1 正常评论；
     * 每条一级评论的 replies 挂前 2 条二级回复，replyCount 为全量子回复数。
     * 分页查询沿用项目 POST + DTO 惯例（对齐 /post/list、/boards/list）。</p>
     */
    @PostMapping("/list")
    @SaIgnore
    public BaseResponse<IPage<CommentVO>> listByPost(@RequestBody CommentPageDTO dto){
        return ResultUtils.success(commentService.listByPost(dto));
    }

    /**
     * 某顶层评论的全部回复分页（对齐文档 §6.5）。
     *
     * <p>WHERE parentId=commentId AND status=1，按创建时间正序分页；
     * isLiked / 作者 / replyToUser 批量组装。分页查询沿用项目 POST + DTO 惯例。</p>
     */
    @PostMapping("/replies")
    @SaIgnore
    public BaseResponse<IPage<CommentVO>> listReplies(@RequestBody CommentReplyPageDTO dto){
        return ResultUtils.success(commentService.listReplies(dto));
    }
}
