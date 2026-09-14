package com.ruwei.innerservice;

import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.Comment;
import com.ruwei.model.entity.Post;
import com.ruwei.model.vo.PostBrowseVO;

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

    /**
     * 按对外唯一编码查询帖子（postCode → 内部 id 解析）。
     *
     * <p>旧单体 {@code LikeServiceImpl} 用
     * {@code postService.lambdaQuery().eq(Post::getPostCode, postCode).one()}
     * 把前端传来的 postCode 解析成内部 postId；拆服务后 post 表归 post 域，
     * interaction / 其他服务必须经本方法解析，不得直接查 post 表。</p>
     *
     * <p>返回实体已含点赞前校验所需的全部字段：{@code id} / {@code userId} / {@code status} /
     * {@code auditStatus} / {@code visibility} / {@code likeCount} / {@code collectCount}。</p>
     *
     * @param postCode 帖子对外编码（如 P100001，可空）
     * @return 帖子实体；postCode 为空或不存在返回 {@code null}
     */
    Post getByPostCode(String postCode);

    /**
     * 帖子冗余计数原子增减（{@code likeCount} / {@code collectCount}）。
     *
     * <p><b>为什么必须走本方法</b>：计数列长在 post 表，post 表归 post 服务，
     * 其他服务禁止直改对方表；且计数必须 DB 层原子自增（{@code setSql} 拼 SQL），
     * 不能「查出来 +1 再存回」（并发下会丢更新）。</p>
     *
     * <p><b>安全约定</b>：{@code column} 会拼进 SQL 片段，provider 侧按白名单收口
     * （当前仅 {@code likeCount} / {@code collectCount}），非白名单值直接返回 {@code false}。
     * 调用方必须传字面常量，禁止拼用户输入。</p>
     *
     * @param postId 帖子内部 id
     * @param column 计数列名：仅支持 {@code likeCount} / {@code collectCount}
     * @param delta  增量（正数加、负数减）
     * @return 是否更新成功（影响行数 &gt; 0）；参数非法或列名不在白名单返回 {@code false}
     */
    boolean incrementPostCount(Long postId, String column, int delta);

    /**
     * 评论冗余计数原子增减（{@code likeCount}）。
     *
     * <p>与 {@link #incrementPostCount(Long, String, int)} 同构，作用于 comment 表；
     * 评论点赞落库后回写 {@code comment.likeCount} 用。</p>
     *
     * @param commentId 评论内部 id
     * @param column    计数列名：仅支持 {@code likeCount}
     * @param delta     增量（正数加、负数减）
     * @return 是否更新成功（影响行数 &gt; 0）；参数非法或列名不在白名单返回 {@code false}
     */
    boolean incrementCommentCount(Long commentId, String column, int delta);

    /**
     * 批量装配列表卡片 VO（PostBrowseVO），按入参 {@code postIds} 的顺序返回。
     *
     * <p>供 interaction 的「我的收藏」列表复用：interaction 自己分页查 {@code post_collect}
     * 得到当页 postId（已按收藏时间倒序），本方法负责把帖子信息装配成前端卡片 VO。</p>
     *
     * <p><b>装配内容</b>：帖子基础字段 + 正文预览 + 作者昵称/头像（Dubbo 批量查 user）+
     * 板块对象（{@code BoardBriefFiller}）+ 话题标签（{@code TagBriefFiller}）+
     * 用户维度动态状态 {@code isLiked} / {@code isCollected}（Dubbo 批量查 interaction）。</p>
     *
     * <p><b>过滤与排序</b>：只保留「已发布」帖子（{@code status=PUBLISHED}，逻辑删除帖子由
     * MyBatis-Plus {@code @TableLogic} 自动排除），查不到的 id 直接跳过；返回顺序与入参一致。</p>
     *
     * <p><b>loginId 必须显式传入</b>：Dubbo provider 线程没有 HTTP 请求上下文，
     * {@code StpUtil} 取不到登录态（会抛 NotLoginException），因此登录态由调用方在
     * HTTP 线程取好后当参数传进来；传 {@code null} 表示游客视角，{@code isLiked} /
     * {@code isCollected} 保持 {@code null}（前端按未赞 / 未收藏渲染）。</p>
     *
     * @param loginId 当前登录用户内部 id（可空）
     * @param postIds 帖子内部 id 列表（有序，如收藏时间倒序）
     * @return 与入参同序的卡片 VO 列表；入参为空或无有效帖子返回空列表（不返回 null）
     */
    List<PostBrowseVO> buildBrowseVOList(Long loginId, List<Long> postIds);
}
