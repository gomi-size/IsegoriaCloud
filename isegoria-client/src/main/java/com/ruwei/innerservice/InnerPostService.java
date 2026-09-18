package com.ruwei.innerservice;

import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.Comment;
import com.ruwei.model.entity.Post;
import com.ruwei.model.entity.Tag;
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

    /**
     * 批量按 id 查板块（关注板块列表组装用）。
     *
     * <p>旧单体 {@code BoardFollowServiceImpl.getFollowBoardList} 直接 {@code boardService.listByIds}；
     * 拆服务后 board 表归 post，需经本方法。查询自带 {@code @TableLogic} 过滤已逻辑删除板块，
     * 返回顺序不保证，调用方需按自己的 boardIds 顺序重排。</p>
     *
     * @param boardIds 板块内部 id 集合
     * @return 板块列表；入参为空返回空列表
     */
    List<Board> listBoardsByIds(Collection<Long> boardIds);

    /**
     * 查某人创建的板块 id 列表（「我创建的板块的粉丝」列表用）。
     *
     * <p>旧单体 {@code boardService.lambdaQuery().eq(Board::getCreatorId, loginId).list()}
     * 取 id；逻辑删除板块自动过滤。</p>
     *
     * @param creatorId 板块创建者内部 id
     * @return 板块内部 id 列表；无板块返回空列表
     */
    List<Long> listBoardIdsByCreatorId(Long creatorId);

    /**
     * 板块冗余计数原子增减（{@code followCount} / {@code postCount}）。
     *
     * <p><b>为什么必须走本方法</b>：旧单体 {@code BoardFollowServiceImpl.incrementBoardFollowCount}
     * 直接 {@code UPDATE board SET followCount = followCount ± 1}，跨服务后 board 表归 post，
     * 必须收口；且必须 DB 层原子自增，不能「查出来 +1 再存回」。</p>
     *
     * <p><b>安全约定</b>：{@code column} 会拼进 SQL，provider 侧按白名单收口
     * （当前支持 {@code followCount} / {@code postCount}），非白名单值返回 {@code false}。</p>
     *
     * @param boardId 板块内部 id
     * @param column  计数列名：仅支持 {@code followCount} / {@code postCount}
     * @param delta   增量（正数加、负数减）
     * @return 是否更新成功（影响行数 &gt; 0）
     */
    boolean incrementBoardCount(Long boardId, String column, int delta);

    /**
     * 召回① 关注流：按作者集合取"最新"帖子 id。
     *
     * <p>可见性口径：已发布 + 审核通过 + 可见性 ∈ {公开, 仅粉丝可见}
     * （关注关系背书，允许"仅粉丝可见"的帖进推荐流）。</p>
     *
     * @param authorIds       作者内部 id 集合（我关注的人）
     * @param includeFansOnly true 时额外放行 visibility=仅粉丝可见（路①专用）；false 时只取公开
     * @param limit           最多返回多少条（按 createdAt 降序取前 N）
     * @return 帖子内部 id 列表（按 createdAt 降序）；入参为空返回空列表
     */
    List<Long> listVisiblePostIdsByAuthorIds(Collection<Long> authorIds, boolean includeFansOnly, int limit);

    /**
     * 召回② 板块流：按板块集合取"最新"帖子 id。
     *
     * <p>可见性口径：已发布 + 审核通过 + <b>仅公开</b>（推荐流没有关注关系背书，
     * 板块里的"仅粉丝可见"帖不进推荐）。</p>
     *
     * @param boardIds 板块内部 id 集合（我关注的板块）
     * @param limit    最多返回条数（按 createdAt 降序）
     * @return 帖子内部 id 列表；入参为空返回空列表
     */
    List<Long> listVisiblePostIdsByBoardIds(Collection<Long> boardIds, int limit);

    /**
     * 召回③ 热点：全库按热度分（{@code score}）取 Top N。
     *
     * <p>可见性口径：已发布 + 审核通过 + 仅公开。是游客 / 冷画像用户的保底内容源。</p>
     *
     * @param limit 最多返回条数（按 score 降序）
     * @return 帖子内部 id 列表
     */
    List<Long> listHotVisiblePostIds(int limit);

    /**
     * 召回④ 标签流的收尾裁剪：把 post_tag 反查出来的候选帖按可见性过滤 + 取最新 N 条。
     *
     * @param postIds 候选帖子内部 id 集合（由 {@link #listPostIdsByTagIds} 反查得到）
     * @param limit   最多返回条数（按 createdAt 降序）
     * @return 帖子内部 id 列表；入参为空返回空列表
     */
    List<Long> listVisiblePostIdsByIds(Collection<Long> postIds, int limit);

    /**
     * 召回⑤ 冷启动：近期新帖 + 浏览数低于阈值，让新人新帖有曝光机会。
     *
     * <p>窗口从 {@code createdAt} 起算而非审核通过时间：先审后发下审核可能晚于创建，
     * 窗口太短会导致帖子刚通过审核就已经滑出冷启动池（旧实现把窗口放宽到 72h 就是为了这个）。</p>
     *
     * @param hours        窗口小时数（旧默认 72）
     * @param maxViewCount 浏览数上限（旧默认走 rec 配置 coldViewThreshold）
     * @param limit        最多返回条数（按 createdAt 降序）
     * @return 帖子内部 id 列表
     */
    List<Long> listColdStartPostIds(int hours, int maxViewCount, int limit);

    // ==================== Phase 8 新增：ES 索引同步 / 对账 ====================

    /**
     * 游标分页取全量帖子 id（按 id 升序），供 ES 全量重建 / 每日对账分批扫描。
     *
     * <p><b>为什么用游标而不是 page 页码</b>：① 不把 MyBatis-Plus 的 {@code Page} 类型带进
     * Dubbo 契约；② 深分页（limit 500000, 500）在 MySQL 上会越来越慢，而
     * {@code WHERE id > #{lastId} ORDER BY id LIMIT n} 走主键索引恒定开销；
     * ③ 扫表期间新增的帖子不会被漏掉（下次从上次的 lastId 继续）。</p>
     *
     * @param lastId 上一批的最后一条 id（首批传 null 或 0）
     * @param limit  本批条数（建议 500）
     * @return 帖子内部 id 列表（升序）；没有更多返回空列表
     */
    List<Long> listPostIdsAfterId(Long lastId, int limit);

    /**
     * 取某作者的全部帖子（含未发布/已下架，供 ES 重建时逐条判断该索引还是该删）。
     *
     * <p>用户改昵称/头像时，要把他的全部帖子重新索引（{@code PostDoc} 冗余了昵称头像）。</p>
     *
     * @param authorId 作者内部 id
     * @return 帖子实体列表；无帖子返回空列表
     */
    List<Post> listPostsByAuthorId(Long authorId);

    /**
     * 取某作者的全部帖子 id（供 ES 失败重试集合写入）。
     *
     * @param authorId 作者内部 id
     * @return 帖子内部 id 列表
     */
    List<Long> listPostIdsByAuthorId(Long authorId);

    /**
     * 批量把「板块对象 + 话题标签」填进卡片 VO（原地填充 + 返回，见决策 2）。
     *
     * @param voList 已装配好基础字段的卡片 VO 列表
     * @return 填充后的同一个列表（Dubbo 跨进程会序列化往返，必须靠返回值取回）
     */
    List<PostBrowseVO> fillBoardAndTags(List<PostBrowseVO> voList);

    /**
     * 批量按 id 查标签实体（ES 文档装配标签名 {@code PostDoc.tagNames} 用）。
     *
     * <p>tag 表归 post 域，rec 侧重建 {@code PostDoc} 时要把 {@code post.topic}（tag id 逗号串）
     * 翻译成标签名冗余进索引，因此需要本方法。<b>与
     * {@link #listPostIdsByTagIds(List)} 方向相反</b>：那个是 tag → postId 反查，这个是 tagId → Tag 正查。</p>
     *
     * <p>注意与 {@code post.assembler.TagBriefFiller} 的口径差异：装配组件另加了
     * {@code tag.status = 1（正常）} 过滤（对外展示口径）；本方法<b>不过滤 status</b>，
     * 因为 ES 索引是"帖子内容快照"，标签被禁用不该让历史索引里的标签名凭空消失。</p>
     *
     * @param tagIds 标签内部 id 集合
     * @return 标签实体列表；入参为空或无命中返回空列表（不返回 null）
     */
    List<Tag> listTagsByIds(Collection<Long> tagIds);
}
