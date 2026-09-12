package com.ruwei.post.service.impl;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.common.mybatis.CountUtils;
import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.innerservice.InnerFollowService;
import com.ruwei.innerservice.InnerInteractionService;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.CommentAddDTO;
import com.ruwei.model.dto.CommentPageDTO;
import com.ruwei.model.dto.CommentReplyPageDTO;
import com.ruwei.model.entity.*;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;
import com.ruwei.model.mq.CommentEventMessage;
import com.ruwei.model.mq.PostIndexMessage;
import com.ruwei.model.mq.ReplyEventMessage;
import com.ruwei.model.vo.AuthorMiniVO;
import com.ruwei.model.vo.CommentVO;
import com.ruwei.post.mapper.CommentMapper;
import com.ruwei.post.service.AuditlogService;
import com.ruwei.post.service.BoardService;
import com.ruwei.post.service.CommentService;
import com.ruwei.post.service.PostService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
* @author Administrator
* @description 针对表【comment(评论表(两级盖楼))】的数据库操作Service实现
* @createDate 2026-08-14 16:44:41
*/
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment>
    implements CommentService {

    /** 评论列表每条一级评论挂出的二级回复预览条数（对齐文档 §6.4） */
    private static final int REPLY_PREVIEW_LIMIT = 2;

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    @Resource
    private PostService postService;

    @DubboReference
    private InnerFollowService innerFollowService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @DubboReference
    private InnerUserService innerUserService;

    @Resource
    private BoardService boardService;

    @DubboReference
    private InnerInteractionService innerInteractionService;

    @Resource
    private AuditlogService auditlogService;

    /**
     * 发表评论/回复（对齐 docs/modules/10-comment-module.md §6.1）：
     * 校验 → 帖子可评校验（status/visibility）→ 敏感词（拦截/审核拒、替换脱敏）→
     * 组装 parentId/replyToUserId → 落库 → 计数 → 发 MQ 通知事件（evt.comment / evt.reply）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addComment(CommentAddDTO commentAddDTO) {
        // 当前登录用户内部 id（= Sa-Token loginId）
        long loginId = StpUtil.getLoginIdAsLong();

        // ===== 1. 参数校验 =====
        ThrowUtils.throwIf(BeanUtil.isEmpty(commentAddDTO), ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        String content = StrUtil.trim(commentAddDTO.getContent());
        ThrowUtils.throwIf(StrUtil.isBlank(content), ErrorCode.PARAMS_ERROR, "评论内容不能为空");
        ThrowUtils.throwIf(content.length() > 1000, ErrorCode.PARAMS_ERROR, "评论最多1000字");
        ThrowUtils.throwIf(StrUtil.isBlank(commentAddDTO.getPostCode()), ErrorCode.PARAMS_ERROR, "帖子编码不能为空");

        // 帖子对外编码 → 内部 postId
        Post post = postService.lambdaQuery()
                .eq(Post::getPostCode, commentAddDTO.getPostCode())
                .one();
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        Long postId = post.getId();

        // ===== 2. 帖子可评校验 =====
        // 仅「已发布」帖子可评论（草稿/审核中/下架一律拒绝）
        ThrowUtils.throwIf(!PostStatusEnum.PUBLISHED.matches(post.getStatus()),
                ErrorCode.OPERATION_ERROR, "帖子当前不可评论");
        // visibility=2 仅粉丝可见 → 需关注作者（关注关系归 social，经 Dubbo 判定）
        if (PostVisibilityEnum.FANS_ONLY.matches(post.getVisibility())) {
            boolean following = innerFollowService.isFollowing(loginId, post.getUserId());
            ThrowUtils.throwIf(!following, ErrorCode.NO_AUTH_ERROR, "你不是该作者的粉丝，请先关注");
        }
        // visibility=3 私密 → 拒绝评论
        ThrowUtils.throwIf(PostVisibilityEnum.PRIVATE.matches(post.getVisibility()),
                ErrorCode.NO_AUTH_ERROR, "该帖子未公开，不可评论");

        // ===== 3. 敏感词处置（评论不人工审核）：拦截/审核直接拒绝，替换用脱敏后文本 =====
        String safeContent = scrub(content, "评论");

        // ===== 4. 组装 parentId / replyToUserId =====
        Long parentId = commentAddDTO.getParentId();
        Long replyToUserId = null;
        if (parentId != null && parentId > 0) {
            // 查父评论：parentId 即父评论 id（二级一律指向顶层评论，含楼中楼互评）
            Comment parent = getById(parentId);
            ThrowUtils.throwIf(BeanUtil.isEmpty(parent) || !Objects.equals(parent.getStatus(), 1),
                    ErrorCode.NOT_FOUND_ERROR, "该上级评论不存在或已删除");
            ThrowUtils.throwIf(!Objects.equals(parent.getPostId(), postId),
                    ErrorCode.OPERATION_ERROR, "评论与帖子不匹配");
            // replyToUserId 缺省取父评论作者（前端只需传 parentId 即可回复）
            replyToUserId = commentAddDTO.getReplyToUserId() != null
                    ? commentAddDTO.getReplyToUserId()
                    : parent.getUserId();
        } else {
            // 一级评论：parentId 归零、replyToUserId 必空
            parentId = 0L;
        }

        // ===== 5. 落库（status=1 正常） =====
        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(loginId);
        comment.setParentId(parentId);
        comment.setReplyToUserId(replyToUserId);
        comment.setContent(safeContent);
        comment.setStatus(1);
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        boolean saved = save(comment);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "评论失败");

        // ===== 6. 事务内计数（CountUtils DB 原子增减） =====
        CountUtils.increment(postService, Post::getId, postId, "commentCount", 1);
        if (parentId > 0) {
            // 二级回复：父评论 replyCount + 1（仅统计 status=1 的子回复）
            CountUtils.increment(this, Comment::getId, parentId, "replyCount", 1);
        }

        // ===== 7. 通知事件改 MQ（notify/rec 消费，消费端幂等） =====
        if (parentId > 0) {
            // 楼中楼回复 → evt.reply（仅 notify 消费）
            rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                    ForumMqConstants.RK_EVT_REPLY,
                    new ReplyEventMessage(postId, comment.getId(),
                            loginId, replyToUserId, post.getUserId(), safeContent));
        } else {
            // 一级评论 → evt.comment（notify 通知作者 + rec 兴趣画像）
            rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                    ForumMqConstants.RK_EVT_COMMENT,
                    new CommentEventMessage(postId, comment.getId(),
                            loginId, post.getUserId(), safeContent));
        }
        // 评论数变更 → es.post.index（rec 消费重建索引）
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_POST_INDEX,
                new PostIndexMessage(postId, PostIndexMessage.ACTION_INDEX));
    }

    /**
     * 删除评论（对齐 docs/modules/10-comment-module.md §6.2 / §9）：
     * 权限四选一（本人 / 帖主 / 吧主 / admin）；顶层评论级联软删子树并回退计数，二级回复软删自身并回退计数；
     * 删除已删评论幂等返回「评论不存在」；非本人操作写 auditLog(targetType=2, action=3) 留痕。
     * 全部操作同一事务，任一失败整体回滚。
     *
     * @param commentId 评论内部 id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId) {
        // 参数校验
        ThrowUtils.throwIf(commentId == null || commentId <= 0, ErrorCode.PARAMS_ERROR, "请求参数非法");
        Comment comment = getById(commentId);
        // 幂等：不存在或已删(status=2)一律视为「评论不存在」
        ThrowUtils.throwIf(BeanUtil.isEmpty(comment) || comment.getStatus() == 2,
                ErrorCode.NOT_FOUND_ERROR, "该评论已被删除");
        long loginId = StpUtil.getLoginIdAsLong();

        // 帖子与板块（吧主判定用；板块不存在则不参与权限判定）
        Long postId = comment.getPostId();
        Post post = postService.getById(postId);
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "该帖子不存在");
        Board board = post.getBoardId() != null ? boardService.getById(post.getBoardId()) : null;

        // 权限四选一：本人 || 帖主 || 吧主(board.creatorId) || admin（对齐文档 §9 权限矩阵）
        boolean isCommenter = loginId == comment.getUserId();
        boolean isPostOwner = loginId == post.getUserId();
        boolean isBoardOwner = BeanUtil.isNotEmpty(board) && loginId == board.getCreatorId();
        ThrowUtils.throwIf(!isCommenter && !isPostOwner && !isBoardOwner
                        && !innerUserService.isAdmin(StpUtil.getLoginIdAsLong()),
                ErrorCode.NO_AUTH_ERROR, "无权限");

        Long parentId = comment.getParentId();
        if (parentId == 0) {
            // 顶层评论：级联软删其下全部二级回复（二级一律指向本评论），计数回退 1 + 子树正常数
            int n = Math.toIntExact(lambdaQuery()
                    .eq(Comment::getParentId, commentId)
                    .eq(Comment::getStatus, 1).count());
            ThrowUtils.throwIf(!lambdaUpdate().eq(Comment::getParentId, commentId)
                            .set(Comment::getStatus, 2).update(),
                    ErrorCode.OPERATION_ERROR, "删除评论失败");
            ThrowUtils.throwIf(!lambdaUpdate().eq(Comment::getId, commentId)
                            .set(Comment::getStatus, 2).update(),
                    ErrorCode.OPERATION_ERROR, "删除评论失败");
            CountUtils.increment(postService, Post::getId, postId, "commentCount", -(1 + n));
        } else {
            // 二级回复：软删自身，帖子计数 -1、顶层父评论 replyCount -1
            ThrowUtils.throwIf(!lambdaUpdate().eq(Comment::getId, commentId)
                            .set(Comment::getStatus, 2).update(),
                    ErrorCode.OPERATION_ERROR, "删除评论失败");
            CountUtils.increment(postService, Post::getId, postId, "commentCount", -1);
            CountUtils.increment(this, Comment::getId, parentId, "replyCount", -1);
        }

        // 非本人操作（帖主/吧主/admin 代删）写审核日志留痕（对齐文档 §6.2 第 4 步）
        if (!isCommenter) {
            saveAuditLog(commentId, loginId);
        }
        //需要更新es里面的数据（评论数变更 → es.post.index 由 rec 消费重建索引）
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_POST_INDEX,
                new PostIndexMessage(postId, PostIndexMessage.ACTION_INDEX));
    }


    /**
     * 帖子评论列表（对齐 docs/modules/10-comment-module.md §6.4，防 N+1）：
     * 一级评论分页（parentId=0, status=1, 创建时间正序——盖楼语义固定，不开放 sortField）+
     * 每条一级评论挂前 2 条二级回复；isLiked / 作者 / replyToUser 全部批量查询组装。
     * SQL 共 5 条：post 解析 + 一级分页 + 子回复 IN + comment_like IN + 用户批量。
     *
     * @param dto 分页参数 + postCode
     * @return 一级评论分页（records 为组装好的 CommentVO，replies 挂前 2 条二级回复）
     */
    @Override
    public IPage<CommentVO> listByPost(CommentPageDTO dto) {
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto) || StrUtil.isBlank(dto.getPostCode()),
                ErrorCode.PARAMS_ERROR, "帖子编码不能为空");
        long loginId = StpUtil.getLoginIdAsLong();

        // postCode → postId
        Post post = postService.lambdaQuery()
                .eq(Post::getPostCode, dto.getPostCode())
                .one();
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        Long postId = post.getId();

        // 1) 一级评论分页（parentId=0, status=1, 创建时间正序）
        Page<Comment> page = this.page(
                new Page<>(dto.getCurrent(), dto.getPageSize()),
                Wrappers.lambdaQuery(Comment.class)
                        .eq(Comment::getPostId, postId)
                        .eq(Comment::getParentId, 0)
                        .eq(Comment::getStatus, 1)
                        .orderByDesc(Comment::getLikeCount)
                        .orderByDesc(Comment::getCreatedAt));
        List<Comment> levelOnes = page.getRecords();

        if (levelOnes.isEmpty()) {
            // 无一级评论：convert 保留 total/current/size 分页元数据，返回空页
            return page.convert(c -> new CommentVO());
        }

        // 2) 子回复一次捞回（parentId IN 本页一级id, status=1），内存按 parentId 分组
        List<Long> levelOneIds = levelOnes.stream().map(Comment::getId).toList();
        List<Comment> replies = lambdaQuery()
                .in(Comment::getParentId, levelOneIds)
                .eq(Comment::getStatus, 1)
                .orderByDesc(Comment::getParentId)
                .orderByDesc(Comment::getLikeCount)
                .orderByDesc(Comment::getCreatedAt)
                .list();
        // groupingBy 保序（SQL 已按 createdAt 正序），replyCount 用 DB 字段，replies 只挂前 2 条
        Map<Long, List<Comment>> replyMap = replies.stream()
                .collect(Collectors.groupingBy(Comment::getParentId, LinkedHashMap::new, Collectors.toList()));

        // 3) isLiked：当前用户对「一级 + 子回复」全部 commentId 经 Dubbo 批量查 interaction（点赞归 interaction）
        List<Comment> allComments = Stream.concat(levelOnes.stream(), replies.stream()).toList();
        List<Long> allCommentIds = allComments.stream().map(Comment::getId).toList();
        Set<Long> likedIds = innerInteractionService.getCommentLikedMap(loginId, allCommentIds).entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // 4) 作者 / replyToUser 批量查（userId + replyToUserId 一并收集，避免 N+1）
        Set<Long> userIds = allComments.stream()
                .flatMap(c -> Stream.of(c.getUserId(), c.getReplyToUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AuthorMiniVO> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : innerUserService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> {
                    AuthorMiniVO vo = new AuthorMiniVO();
                    vo.setId(u.getId());
                    vo.setUserId(u.getUserId());
                    vo.setNickname(u.getNickname());
                    vo.setAvatar(u.getAvatar());
                    return vo;
                }, (a, b) -> a));

        // 5) 组装 VO（作者/replyToUser 查不到置 null 由前端兜底；convert 保留分页元数据）
        return page.convert(comment -> toCommentVO(comment, post.getPostCode(), userMap, likedIds,
                replyMap.getOrDefault(comment.getId(), Collections.emptyList())));
    }



    /**
     * 某顶层评论的全部回复分页（对齐 docs/modules/10-comment-module.md §6.5）：
     * WHERE parentId=? AND status=1 ORDER BY createdAt ASC 分页；
     * isLiked / 作者 / replyToUser 批量组装（同 §6.4，防 N+1）。
     *
     * @param dto 分页参数 + 顶层评论 commentId
     * @return 二级回复分页（records 为组装好的 CommentVO，不挂 replies）
     */
    @Override
    public IPage<CommentVO> listReplies(CommentReplyPageDTO dto) {
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto) || dto.getCommentId() == null || dto.getCommentId() <= 0,
                ErrorCode.PARAMS_ERROR, "评论id不能为空");
        long loginId = StpUtil.getLoginIdAsLong();

        // 顶层评论存在性校验，并取 postCode（该评论下所有回复同属一帖）
        Comment parent = getById(dto.getCommentId());
        ThrowUtils.throwIf(BeanUtil.isEmpty(parent), ErrorCode.NOT_FOUND_ERROR, "评论不存在");
        Post post = postService.getById(parent.getPostId());
        String postCode = post == null ? null : post.getPostCode();

        // 1) 二级回复分页（parentId=顶层id, status=1, 创建时间正序——盖楼语义固定）
        Page<Comment> page = this.page(
                new Page<>(dto.getCurrent(), dto.getPageSize()),
                Wrappers.lambdaQuery(Comment.class)
                        .eq(Comment::getParentId, dto.getCommentId())
                        .eq(Comment::getStatus, 1)
                        .orderByDesc(Comment::getLikeCount)
                        .orderByDesc(Comment::getCreatedAt));
        List<Comment> records = page.getRecords();
        if (records.isEmpty()) {
            // 无回复：convert 保留 total/current/size 分页元数据，返回空页
            return page.convert(c -> new CommentVO());
        }

        // 2) isLiked：本页全部 commentId 经 Dubbo 批量查 interaction（点赞归 interaction）
        List<Long> commentIds = records.stream().map(Comment::getId).toList();
        Set<Long> likedIds = innerInteractionService.getCommentLikedMap(loginId, commentIds).entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // 3) 作者 / replyToUser 批量查（userId + replyToUserId 一并收集，避免 N+1）
        Set<Long> userIds = records.stream()
                .flatMap(c -> Stream.of(c.getUserId(), c.getReplyToUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AuthorMiniVO> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : innerUserService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> {
                            AuthorMiniVO vo = new AuthorMiniVO();
                            vo.setId(u.getId());
                            vo.setUserId(u.getUserId());
                            vo.setNickname(u.getNickname());
                            vo.setAvatar(u.getAvatar());
                            return vo;
                        }, (a, b) -> a));

        // 4) 组装 VO（不挂 replies；作者/replyToUser 查不到置 null 由前端兜底）
        return page.convert(comment -> toCommentVO(comment, postCode, userMap, likedIds, null));
    }

    /**
     * Comment → CommentVO 组装。replies 非空时递归挂前 {@link #REPLY_PREVIEW_LIMIT} 条二级回复（不再递归挂三级）。
     */
    private CommentVO toCommentVO(Comment comment, String postCode, Map<Long, AuthorMiniVO> userMap,
                                  Set<Long> likedIds, List<Comment> replies) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setPostCode(postCode);
        vo.setUser(userMap.get(comment.getUserId()));
        vo.setContent(comment.getContent());
        vo.setParentId(comment.getParentId());
        vo.setReplyToUser(comment.getReplyToUserId() == null ? null : userMap.get(comment.getReplyToUserId()));
        vo.setLikeCount(comment.getLikeCount());
        vo.setReplyCount(comment.getReplyCount());
        vo.setIsLiked(likedIds.contains(comment.getId()));
        vo.setStatus(comment.getStatus());
        vo.setCreatedAt(comment.getCreatedAt());
        if (replies != null && !replies.isEmpty()) {
            vo.setReplies(replies.stream()
                    .limit(REPLY_PREVIEW_LIMIT)
                    .map(r -> toCommentVO(r, postCode, userMap, likedIds, null))
                    .toList());
        }
        return vo;
    }

    /**
     * 敏感词扫描与处置（对齐文档 §6.1 步骤 3）：
     * 命中拦截词/审核词直接拒绝（评论不人工审核）；命中替换词脱敏为 ***；放行保留原文。
     */
    private String scrub(String text, String fieldName) {
        SensitiveWordFilter.FilterResult fr = sensitiveWordFilter.filter(text);
        // 拦截 / 审核 同处置：评论无草稿、无审核流，直接拒绝
        ThrowUtils.throwIf(fr.action == SensitiveWordFilter.SensitiveAction.INTERCEPT
                        || fr.action == SensitiveWordFilter.SensitiveAction.REVIEW,
                ErrorCode.PARAMS_ERROR, fieldName + "包含敏感或违规内容，请修改后重试");
        if (fr.action == SensitiveWordFilter.SensitiveAction.REPLACED) {
            return fr.processedText;
        }
        return text;
    }

    /**
     * 审核日志落地：记录非本人对评论的删除动作。
     *
     * <p>字段语义（对齐 auditLog 表）：targetType=2 评论；action=3 删除；
     * adminId 为实际操作者内部 id，remark 说明删除动作。与删除操作同一事务，失败整体回滚。</p>
     */
    private void saveAuditLog(Long commentId, Long loginId) {
        Auditlog log = new Auditlog();
        log.setAdminId(loginId);
        log.setTargetType(2);
        log.setTargetId(commentId);
        log.setAction(3);
        log.setRemark("删除评论");
        log.setCreatedAt(new Date());
        auditlogService.save(log);
    }

}
