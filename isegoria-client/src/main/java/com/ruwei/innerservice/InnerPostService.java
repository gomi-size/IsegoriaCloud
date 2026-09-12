package com.ruwei.innerservice;

import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.Comment;
import com.ruwei.model.entity.Post;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** post 服务提供（InnerPostServiceImpl @DubboService） */
public interface InnerPostService {
    /**
     * 根据id获取帖子
     * @param id
     * @return
     */
    Post getById(Serializable id);

    /**
     * 批量获取帖子
     * @param ids
     * @return
     */
    List<Post> listByIds(Collection<? extends Serializable> ids);

    /**
     * 获取评论
     * @param commentId
     * @return
     */
    Comment getCommentById(Long commentId);

    /**
     * 获取板块
     * @param boardId
     * @return
     */
    Board getBoardById(Long boardId);

    /**
     * rec 召回路④ 通过帖子标签获取帖子
     * @param tagIds
     * @return
     */
    List<Long> listPostIdsByTagIds(List<Long> tagIds);   //

    /**
     *  rec 召回路② 根据板块获取贴子
     * @param boardIds
     * @return
     */
    List<Long> listPostIdsByBoardIds(List<Long> boardIds); //
}
