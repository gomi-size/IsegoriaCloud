package com.ruwei.post.inner;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.apache.dubbo.config.annotation.DubboService;

import com.ruwei.innerservice.InnerPostService;
import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.Comment;
import com.ruwei.model.entity.Post;
import com.ruwei.model.entity.PostTag;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.post.service.BoardService;
import com.ruwei.post.service.CommentService;
import com.ruwei.post.service.PostService;
import com.ruwei.post.service.PostTagService;

import jakarta.annotation.Resource;

/**
 * 帖子契约的 Dubbo provider（供 interaction / social / rec / notify 等服务远程调用）。
 *
 * <p>全部为按 id / 条件的幂等读，不校验调用方登录态；
 * 查询条件与旧单体各调用点保持一致（如 {@code listPostIdsByTagIds} 抄自 RecServiceImpl 的召回条件）。</p>
 *
 * @author ruwei
 */
@DubboService
public class InnerPostServiceImpl implements InnerPostService {

    @Resource
    private PostService postService;

    @Resource
    private CommentService commentService;

    @Resource
    private BoardService boardService;

    @Resource
    private PostTagService postTagService;

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
        // 抄 RecServiceImpl 召回条件：标签关联（仅已发布版本）→ 去重帖子 id
        return postTagService.lambdaQuery()
                .in(PostTag::getTagId, tagIds)
                .eq(PostTag::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .list().stream().map(PostTag::getPostId).distinct().toList();
    }

    @Override
    public List<Long> listPostIdsByBoardIds(List<Long> boardIds) {
        return postService.lambdaQuery()
                .in(Post::getBoardId, boardIds)
                .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .list().stream().map(Post::getId).toList();
    }
}
