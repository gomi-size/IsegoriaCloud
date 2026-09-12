package com.ruwei.post.service.impl;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.common.mybatis.CountUtils;
import com.ruwei.common.mybatis.QueryWrapperUtils;
import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.innerservice.InnerFollowService;
import com.ruwei.innerservice.InnerInteractionService;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.AdminPostStatusDTO;
import com.ruwei.model.dto.ContentBlock;
import com.ruwei.model.dto.PostDTO;
import com.ruwei.model.dto.PostQueryDTO;
import com.ruwei.model.entity.*;
import com.ruwei.model.enums.PostAuditStatusEnum;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;
import com.ruwei.model.mq.AdminEventMessage;
import com.ruwei.model.mq.PostEventMessage;
import com.ruwei.model.mq.PostIndexMessage;
import com.ruwei.model.mq.ShareEventMessage;
import com.ruwei.model.mq.ViewEventMessage;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.model.vo.PostVO;
import com.ruwei.model.vo.TagVO;
import com.ruwei.post.assembler.BoardBriefFiller;
import com.ruwei.post.assembler.TagBriefFiller;
import com.ruwei.post.mapper.PostMapper;
import com.ruwei.post.mapper.PostShareMapper;
import com.ruwei.post.service.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
* @author Administrator
* @description 针对表【post(帖子/笔记表(推荐系统物料主表))】的数据库操作Service实现
* @createDate 2026-08-05 10:16:16
*
* <p>实现约定（与 PostService 接口注释一致）：</p>
* <ul>
*   <li>作者一律取当前登录态内部 id（= Sa-Token loginId），不信任前端传参；</li>
*   <li>创建送审：status=3 + auditStatus=1；编辑「先审后发」：直接改正式字段并重新置为审核中；</li>
*   <li>图片/标签<b>按版本状态分组</b>：post_image.status / post_tag.status 记录该行属于帖子的哪个版本
*       （已发布 / 草稿 / 审核中）。写入时只在同一 status 内全量替换（先查、无则插、有则删旧插新），
*       其它版本不受影响；审核结束由 migrateReviewingRelations 把「审核中」版整体顶替为最终状态；</li>
*   <li>计数口径（user.postCount / board.postCount）：仅「已发布」计入——创建送审不加、审核通过 +1；
*       编辑已发布帖送审时回收（-1）、通过后恢复；驳回不计数也不回收；删除仅对「已发布」帖子回退；</li>
*   <li>审核只推进状态、不搬运内容：待审内容在创建/编辑时已落在正式字段上，
*       通过→已发布、驳回→下架（正式字段已被覆盖，无旧版本可回退）；</li>
*   <li>对外编码 postCode 基于 Redis 原子自增（key {@code isegoria:post:code:counter}，前缀 P）。</li>
* </ul>
*/
@Slf4j
@Service
public class PostServiceImpl extends ServiceImpl<PostMapper, Post>
    implements PostService {

    /** postCode 计数器 Redis key（对外编码，对齐 userId 的生成方式） */
    private static final String POST_CODE_COUNTER_KEY = "isegoria:post:code:counter";

    /** postCode 自增起始基准 */
    private static final long POST_CODE_BASE = 100000L;

    /** 列表卡片预览正文最大字符数（超出截断并追加省略号） */
    private static final int PREVIEW_MAX_LENGTH = 100;

    @Resource
    private PostImageService postImageService;

    @Resource
    private TagService tagService;

    @Resource
    private PostTagService postTagService;

    @Resource
    private BoardService boardService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 板块信息批量装配（PostBrowseVO 的 board 对象 + 兼容字段 boardName/boardSlug，防 N+1）
     */
    @Resource
    private BoardBriefFiller boardBriefFiller;

    /**
     * 话题标签批量装配（PostBrowseVO 的 tags 列表，post_tag + tag 两表批量查，防 N+1）
     */
    @Resource
    private TagBriefFiller tagBriefFiller;

    @DubboReference
    private InnerUserService innerUserService;

    @Resource
    private AuditlogService auditlogService;

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @DubboReference
    private InnerFollowService innerFollowService;

    @Resource
    private ViewHistoryService viewHistoryService;

    /** 帖子分享流水表（站外/站内分享埋点直插，无关系语义） */
    @Resource
    private PostShareMapper postShareMapper;

    /**
     * 点赞/收藏状态批量装配（点赞、收藏归 interaction 服务，经 Dubbo 批量查询）。
     */
    @DubboReference
    private InnerInteractionService innerInteractionService;

    /**
     * 创建帖子（送审）。返回对外展示的 {@link PostVO}（枚举字段回显文字、雪花 id 转字符串）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostVO createPost(PostDTO dto) {
        // 当前登录用户内部 id（= Sa-Token loginId）
        long loginId = StpUtil.getLoginIdAsLong();

        // ===== 草稿分支：保存为「新建草稿」（draftOfId=null 槽位），不送审、不强制标题/内容非空 =====
        if (PostStatusEnum.DRAFT.getText().equals(dto.getStatus())) {
            return saveAsNewDraft(dto, loginId);
        }

        // 1. 参数校验
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto), ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(dto.getTitle()), ErrorCode.PARAMS_ERROR, "标题不能为空");
        ThrowUtils.throwIf(dto.getTitle().length() > 200, ErrorCode.PARAMS_ERROR, "标题最多200字");
        // 内容非空校验：纯文本 content 非空 或 blocks 中存在文本块
        boolean hasBlockText = dto.getContentBlocks() != null
                && dto.getContentBlocks().stream().anyMatch(b -> StrUtil.isNotBlank(b.getText()));
        ThrowUtils.throwIf(StrUtil.isBlank(dto.getContent()) && !hasBlockText,
                ErrorCode.PARAMS_ERROR, "内容不能为空");
        if (StrUtil.isNotBlank(dto.getContent())) {
            ThrowUtils.throwIf(dto.getContent().length() > 10000000, ErrorCode.PARAMS_ERROR, "内容最多10000000字");
        }

        // 2. 板块存在性校验（boardId 非空时）
        if (dto.getBoardId() != null) {
            Board board = boardService.getById(dto.getBoardId());
            ThrowUtils.throwIf(BeanUtil.isEmpty(board), ErrorCode.NOT_FOUND_ERROR, "板块不存在");
        }

        // 3. 敏感词过滤（拦截即拒；替换词脱敏后存储）。content 由 blocks 序列化或纯文本兜底
        String title = scrub(dto.getTitle(), "标题");
        String content = resolveContent(dto);

        // 3.5 话题/标签：前端传 tag id 列表 → 查 tag 表（仅 status=1 未被禁用）→ 名称逗号分隔存 post.topic
        List<Tag> tags = resolveTags(dto.getTopicList());
        String topic = tags.isEmpty() ? null : joinTagNames(tags);

        // 4. 组装并落库（创建送审：status=3 审核中 + auditStatus=1 待审）
        Post post = new Post();
        post.setPostCode(generatePostCode());
        post.setUserId(loginId);
        post.setBoardId(dto.getBoardId());
        post.setTitle(title);
        post.setContent(content);
        post.setCover(dto.getCover());
        post.setType(dto.getType() == null ? 1 : dto.getType());
        post.setVideoUrl(dto.getVideoUrl());
        post.setTopic(topic);
        // 可见性：前端传文字（"公开"/"仅粉丝可见"/"私密"），经枚举转整数；缺省按"公开"(1)
        Integer visibilityCode = PostVisibilityEnum.codeOfText(dto.getVisibility());
        ThrowUtils.throwIf(StrUtil.isNotBlank(dto.getVisibility()) && visibilityCode == null,
                ErrorCode.PARAMS_ERROR, "非法的可见性：" + dto.getVisibility());
        post.setVisibility(visibilityCode == null ? PostVisibilityEnum.PUBLIC.getCode() : visibilityCode);
        // 创建送审：status=审核中(3) + auditStatus=待审(1)（与枚举常量对齐）
        post.setStatus(PostStatusEnum.REVIEWING.getCode());
        post.setAuditStatus(PostAuditStatusEnum.PENDING.getCode());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCollectCount(0);
        post.setViewCount(0);
        post.setShareCount(0);
        post.setIsTop(0);
        post.setIsEssence(0);
        post.setLatitude(dto.getLatitude());
        post.setLongitude(dto.getLongitude());
        post.setLocationName(dto.getLocationName());
        boolean saved = save(post);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "发布失败");

        // 5. 图片：新数据（contentBlocks）已内联进 content，不写 post_image；
        //    旧客户端（仅传 imageUrl）仍按原逻辑写 post_image，向后兼容
        if (dto.getContentBlocks() == null || dto.getContentBlocks().isEmpty()) {
            saveImages(post.getId(), dto.getImageUrl(), PostStatusEnum.REVIEWING.getCode());
        }

        // 6. 标签关联（useCount+1 + post_tag）——同上，标记为「审核中」版本
        bindTags(post.getId(), tags, PostStatusEnum.REVIEWING.getCode());

        // 7. 计数口径：创建送审（审核中）不计数——仅「已发布」计入 user.postCount / board.postCount，
        //    审核通过时由 auditPost +1；编辑送审/删除的回收见 updatePost / deletePost
        // 8. 装配对外 VO：枚举字段回显文字、topic 解析为 id 列表、图片按 sort 回读
        return buildPostVO(post);
    }

    /**
     * 保存「新建草稿」：同一用户仅保留一条 draftOfId=null 的草稿（槽位 upsert）。
     *
     * <p>草稿不送审、不强制标题/内容非空、不做标题重复校验（用户可能先写一半）；
     * 内容照常过敏感词（拦截即拒、替换词脱敏存储），板块存在性照常校验。</p>
     */
    private PostVO saveAsNewDraft(PostDTO dto, long loginId) {
        // 板块存在性校验（选了板块时）
        if (dto.getBoardId() != null) {
            Board board = boardService.getById(dto.getBoardId());
            ThrowUtils.throwIf(BeanUtil.isEmpty(board), ErrorCode.NOT_FOUND_ERROR, "板块不存在");
        }

        String title = scrub(dto.getTitle(), "标题");
        String content = resolveContent(dto);
        List<Tag> tags = resolveTags(dto.getTopicList());
        String topic = tags.isEmpty() ? null : joinTagNames(tags);
        Integer visibilityCode = PostVisibilityEnum.codeOfText(dto.getVisibility());
        ThrowUtils.throwIf(StrUtil.isNotBlank(dto.getVisibility()) && visibilityCode == null,
                ErrorCode.PARAMS_ERROR, "非法的可见性：" + dto.getVisibility());

        // 槽位查询：该用户是否已有「新建草稿」（draftOfId IS NULL）
        Post draft = lambdaQuery()
                .eq(Post::getUserId, loginId)
                .eq(Post::getStatus, PostStatusEnum.DRAFT.getCode())
                .isNull(Post::getDraftOfId)
                .one();

        if (draft == null) {
            draft = new Post();
            draft.setPostCode(generatePostCode());
            draft.setUserId(loginId);
            draft.setDraftOfId(null);
            draft.setStatus(PostStatusEnum.DRAFT.getCode());
            draft.setAuditStatus(PostAuditStatusEnum.PENDING.getCode());
            draft.setLikeCount(0);
            draft.setCommentCount(0);
            draft.setCollectCount(0);
            draft.setViewCount(0);
            draft.setShareCount(0);
            draft.setIsTop(0);
            draft.setIsEssence(0);
        }

        // 覆盖本次传入的内容字段（空标题落空串，草稿允许未写完）
        draft.setBoardId(dto.getBoardId());
        draft.setTitle(title == null ? "" : title);
        draft.setContent(content);
        if (dto.getCover() != null) {
            draft.setCover(dto.getCover());
        }
        if (dto.getType() != null) {
            draft.setType(dto.getType());
        }
        if (dto.getVideoUrl() != null) {
            draft.setVideoUrl(dto.getVideoUrl());
        }
        if (topic != null) {
            draft.setTopic(topic);
        }
        if (visibilityCode != null) {
            draft.setVisibility(visibilityCode);
        }
        draft.setLatitude(dto.getLatitude());
        draft.setLongitude(dto.getLongitude());
        draft.setLocationName(dto.getLocationName());

        boolean saved = draft.getId() != null ? updateById(draft) : save(draft);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "草稿保存失败");

        // 图片/标签按草稿版本维护：旧客户端（仅传 imageUrl）写 post_image；新数据已内联进 content
        if ((dto.getContentBlocks() == null || dto.getContentBlocks().isEmpty())
                && dto.getImageUrl() != null) {
            rebuildImages(draft.getId(), dto.getImageUrl(), PostStatusEnum.DRAFT.getCode());
        }
        if (!tags.isEmpty()) {
            rebuildTags(draft.getId(), tags, PostStatusEnum.DRAFT.getCode());
        }
        return buildPostVO(draft);
    }

    /**
     * 保存「编辑草稿」：同一用户对同一原帖（draftOfId=原帖id）仅保留一条草稿，槽位 upsert。
     *
     * <p>草稿不送审、不对外展示；发布走 {@link #publishDraft(Long, PostDTO)}。</p>
     *
     * @return 草稿 id（字符串），前端可用于跳转草稿编辑态
     */
    private BaseResponse<String> saveAsEditDraft(PostDTO dto, Post original, long loginId,
                                                 String title, String content, String topic,
                                                 List<Tag> topicTags) {
        // 槽位查询：同用户对同一原帖是否已有草稿
        Post draft = lambdaQuery()
                .eq(Post::getUserId, loginId)
                .eq(Post::getStatus, PostStatusEnum.DRAFT.getCode())
                .eq(Post::getDraftOfId, dto.getId())
                .one();

        if (draft == null) {
            draft = new Post();
            draft.setPostCode(dto.getPostCode());
            draft.setUserId(loginId);
            draft.setDraftOfId(dto.getId());
            draft.setStatus(PostStatusEnum.DRAFT.getCode());
            draft.setAuditStatus(PostAuditStatusEnum.PENDING.getCode());
            draft.setLikeCount(0);
            draft.setCommentCount(0);
            draft.setCollectCount(0);
            draft.setViewCount(0);
            draft.setShareCount(0);
            draft.setIsTop(0);
            draft.setIsEssence(0);
        }

        // 覆盖本次传入的内容字段
        draft.setBoardId(dto.getBoardId());
        // 标题：本次传入（已过敏感词）非空则覆盖，否则沿用已有草稿/原帖
        draft.setTitle(StrUtil.isNotBlank(title)
                ? title
                : (StrUtil.isNotBlank(draft.getTitle()) ? draft.getTitle() : original.getTitle()));
        // 内容：本次传入优先；未变更时保留草稿已有内容，再回退原帖
        draft.setContent(content != null
                ? content
                : (draft.getContent() != null ? draft.getContent() : original.getContent()));
        if (dto.getCover() != null) {
            draft.setCover(dto.getCover());
        }
        if (dto.getType() != null) {
            draft.setType(dto.getType());
        }
        if (dto.getVideoUrl() != null) {
            draft.setVideoUrl(dto.getVideoUrl());
        }
        if (topic != null) {
            draft.setTopic(topic);
        } else {
            draft.setTopic(original.getTopic());
        }
        // 可见性：前端传文字 → 枚举转整数；仅在本次传入时覆盖，否则沿用原帖
        if (StrUtil.isNotBlank(dto.getVisibility())) {
            Integer vc = PostVisibilityEnum.codeOfText(dto.getVisibility());
            ThrowUtils.throwIf(vc == null, ErrorCode.PARAMS_ERROR, "非法的可见性：" + dto.getVisibility());
            draft.setVisibility(vc);
        } else {
            draft.setVisibility(original.getVisibility());
        }
        draft.setLatitude(dto.getLatitude());
        draft.setLongitude(dto.getLongitude());
        draft.setLocationName(dto.getLocationName());

        boolean saved = draft.getId() != null ? updateById(draft) : save(draft);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "草稿保存失败");

        // 图片/标签按「草稿自身的 id + 草稿状态」全量替换（必须用 draft.getId()，用原帖 id 会误删原帖数据）
        if ((dto.getContentBlocks() == null || dto.getContentBlocks().isEmpty())
                && dto.getImageUrl() != null) {
            rebuildImages(draft.getId(), dto.getImageUrl(), PostStatusEnum.DRAFT.getCode());
        }
        if (topicTags != null) {
            rebuildTags(draft.getId(), topicTags, PostStatusEnum.DRAFT.getCode());
        }
        return ResultUtils.success(StrUtil.toString(draft.getId()));
    }

    /**
     * 编辑帖子（先发后审；草稿直改）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<String> updatePost(PostDTO dto) {
        long loginId = StpUtil.getLoginIdAsLong();

        ThrowUtils.throwIf(BeanUtil.isEmpty(dto) || dto.getId() == null, ErrorCode.PARAMS_ERROR, "参数不能为空");

        //三个唯一标识联合查询，出现不存在就报错
        Post post = lambdaQuery().eq(Post::getId,dto.getId())
                        .eq(Post::getPostCode,dto.getPostCode())
                        .eq(Post::getUserId,loginId)
                        .one();

        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        ThrowUtils.throwIf(!Objects.equals(post.getUserId(), loginId), ErrorCode.NO_AUTH_ERROR, "只能编辑自己的帖子");

        // 敏感词过滤（编辑的内容同样拦截/脱敏）；blocks 优先，纯文本兜底（无内容变更返回 null）
        String title = StrUtil.isBlank(dto.getTitle()) ? null : scrub(dto.getTitle(), "标题");
        String content = resolveContent(dto);

        // 内容字数限制（最多1500字）
        if (StrUtil.isNotBlank(dto.getContent())) {
            ThrowUtils.throwIf(dto.getContent().length() > 1500, ErrorCode.PARAMS_ERROR, "内容最多1500字");
        }

        // 维护话题：tag id 列表 → 查 tag 表（仅未禁用）→ 名称字符串（null 表示本次未传 topic，不更新）
        List<Tag> topicTags = dto.getTopicList() == null ? null : resolveTags(dto.getTopicList());
        String topic = topicTags == null ? null : joinTagNames(topicTags);

        // ===== 草稿：写入「编辑草稿槽位」（同一原帖最多一条草稿，upsert），不走审核 =====
        if (PostStatusEnum.DRAFT.getText().equals(dto.getStatus())) {
            return saveAsEditDraft(dto, post, loginId, title, content, topic, topicTags);
        }

        // ===== 非草稿：直接改正式字段，并整体重新送审（审核中 + 待审）=====
        // 语义：编辑即下架送审——审核期间 status=审核中，前台不展示；审核通过才恢复已发布。
        // 可见性：前端传文字 → 枚举转整数；仅在本次传入时覆盖
        Integer visibilityCode = null;
        if (StrUtil.isNotBlank(dto.getVisibility())) {
            visibilityCode = PostVisibilityEnum.codeOfText(dto.getVisibility());
            ThrowUtils.throwIf(visibilityCode == null, ErrorCode.PARAMS_ERROR, "非法的可见性：" + dto.getVisibility());
        }

        LambdaUpdateWrapper<Post> uw = new LambdaUpdateWrapper<>();
        uw.eq(Post::getId, post.getId())
          .set(title != null, Post::getTitle, title)
          .set(content != null, Post::getContent, content)
          .set(dto.getCover() != null, Post::getCover, dto.getCover())
          .set(topic != null, Post::getTopic, topic)
          .set(visibilityCode != null, Post::getVisibility, visibilityCode)
          .set(Post::getStatus, PostStatusEnum.REVIEWING.getCode())
          .set(Post::getAuditStatus, PostAuditStatusEnum.PENDING.getCode());
        boolean updated = update(uw);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "编辑失败");

        // 计数口径：编辑即下架送审（已发布→审核中），若原帖为「已发布」（已计入），回收板块/作者计数；
        // 审核通过后由 auditPost 重新 +1，驳回则保持回收（帖子对外不可见，不计入作品数）
        if (PostStatusEnum.PUBLISHED.matches(post.getStatus())) {
            if (post.getBoardId() != null) {
                CountUtils.increment(boardService, Board::getId, post.getBoardId(), "postCount", -1);
            }
            // 作者 postCount 归 user 服务维护，经 Dubbo 原子增减
            innerUserService.incrementUserCount(loginId, "postCount", -1);
        }

        // 图片 / 标签写入「审核中」版本：仅旧客户端（仅传 imageUrl）写 post_image；
        // 新数据（contentBlocks）图片已内联进 content，无需写 post_image
        if (dto.getContentBlocks() == null || dto.getContentBlocks().isEmpty()) {
            if (dto.getImageUrl() != null) {
                rebuildImages(post.getId(), dto.getImageUrl(), PostStatusEnum.REVIEWING.getCode());
            }
        }
        if (topicTags != null) {
            rebuildTags(post.getId(), topicTags, PostStatusEnum.REVIEWING.getCode());
        }
        // 编辑即下架送审：原帖若在索引中，先删除，审核通过后由 auditPost 重新索引
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_POST_INDEX,
                new PostIndexMessage(post.getId(), PostIndexMessage.ACTION_DELETE));
        return ResultUtils.success("修改成功，已提交审核");
    }

    /**
     * 设置帖子可见性（作者本人操作）。前端传文字（"公开"/"仅粉丝可见"/"私密"），经枚举转整数。
     *
     * <p>只改 visibility，<b>不触碰 status / auditStatus</b>：生命周期由审核流单向推进，
     * 作者能自由支配的只有「谁能看到我的帖子」这一维度。</p>
     */
    @Override
    public void updatePostVisibility(Long id, String visibilityText) {
        long loginId = StpUtil.getLoginIdAsLong();

        ThrowUtils.throwIf(id == null || StrUtil.isBlank(visibilityText), ErrorCode.PARAMS_ERROR, "参数不能为空");
        // 文字 → 枚举（非法文字直接拒绝，不落脏数据）
        PostVisibilityEnum target = PostVisibilityEnum.getByText(visibilityText);
        ThrowUtils.throwIf(target == null, ErrorCode.PARAMS_ERROR, "非法的可见性：" + visibilityText);

        Post post = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        ThrowUtils.throwIf(!Objects.equals(post.getUserId(), loginId), ErrorCode.NO_AUTH_ERROR, "只能操作自己的帖子");

        // 幂等：已是目标可见性，直接返回（避免 update 影响 0 行被误判为「设置失败」）
        if (target.matches(post.getVisibility())) {
            return;
        }

        boolean updated = lambdaUpdate()
                .eq(Post::getId, id)
                .set(Post::getVisibility, target.getCode())
                .update();
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "设置可见性失败");

        // 可见性变更同步 ES：设私密/仅粉丝→删除；设公开且已发布→索引
        syncPostToEs(id);
    }

    /**
     * 置顶/取消置顶帖子（作者本人，仅已发布可置顶）。
     *
     * <p>置顶标记只在主页场景（按 userId 查自己/他人帖子列表）体现排序，首页/关注流不体现
     * （排序在 QueryWrapperUtils.getPostQueryWrapper 内按 userId 条件决定）。</p>
     */
    @Override
    public void updatePostTop(Long id, Boolean isTop) {
        long loginId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id == null || isTop == null, ErrorCode.PARAMS_ERROR, "参数不能为空");

        Post post = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        ThrowUtils.throwIf(!Objects.equals(post.getUserId(), loginId), ErrorCode.NO_AUTH_ERROR, "只能置顶自己的帖子");
        ThrowUtils.throwIf(!PostStatusEnum.PUBLISHED.matches(post.getStatus()),
                ErrorCode.OPERATION_ERROR, "只有已发布的帖子才能置顶");

        int target = isTop ? 1 : 0;
        // 幂等：已是目标置顶状态，直接返回（避免 update 影响 0 行被误判为失败）
        if (post.getIsTop() != null && post.getIsTop() == target) {
            return;
        }

        boolean updated = lambdaUpdate()
                .eq(Post::getId, id)
                .set(Post::getIsTop, target)
                .update();
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "设置置顶失败");

        // 置顶变更同步 ES：刷新索引中的 isTop
        syncPostToEs(id);
    }

    /**
     * 设置/取消精华（管理员，仅已发布可设精华）。
     *
     * <p>管理员权限由 Controller 层 {@code @SaCheckRole("admin")} 保证；
     * 与置顶口径一致，仅「已发布」帖子可设精华。</p>
     */
    @Override
    public void updatePostEssence(Long id, Boolean isEssence) {
        ThrowUtils.throwIf(id == null || isEssence == null, ErrorCode.PARAMS_ERROR, "参数不能为空");

        Post post = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        ThrowUtils.throwIf(!PostStatusEnum.PUBLISHED.matches(post.getStatus()),
                ErrorCode.OPERATION_ERROR, "只有已发布的帖子才能设为精华");

        int target = isEssence ? 1 : 0;
        // 幂等：已是目标精华状态，直接返回
        if (post.getIsEssence() != null && post.getIsEssence() == target) {
            return;
        }

        boolean updated = lambdaUpdate()
                .eq(Post::getId, id)
                .set(Post::getIsEssence, target)
                .update();
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "设置精华失败");

        // 精华变更同步 ES：刷新索引中的 isEssence
        syncPostToEs(id);
    }

    /**
     * 删除帖子（逻辑删除 + 关联清理）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long id) {
        long loginId = StpUtil.getLoginIdAsLong();

        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "帖子 id 不能为空");
        Post post = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        ThrowUtils.throwIf(!Objects.equals(post.getUserId(), loginId) && !innerUserService.isAdmin(loginId),
                ErrorCode.NO_AUTH_ERROR, "无权限删除该帖子");

        // 1. 逻辑删除（@TableLogic → isDelete=1）
        boolean removed = removeById(id);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "删除失败");

        // 2. 清理关联：post_image 物理删、post_tag 物理删 + 标签 useCount 回退
        postImageService.remove(new LambdaQueryWrapper<PostImage>().eq(PostImage::getPostId, id));
        List<PostTag> postTags = postTagService.lambdaQuery().eq(PostTag::getPostId, id).list();
        for (PostTag pt : postTags) {
            tagService.lambdaUpdate().eq(Tag::getId, pt.getTagId())
                    .setSql("useCount = useCount - 1").update();
        }
        postTagService.remove(new LambdaQueryWrapper<PostTag>().eq(PostTag::getPostId, id));

        // 3. 计数口径：仅「已发布」帖子曾计入板块/作者计数，删除时才回收；
        //    审核中/草稿/下架本未计数（或已回收），删除不回退。
        //    作者 id 用 post.getUserId()——兼容管理员代删，避免扣到管理员头上
        if (PostStatusEnum.PUBLISHED.matches(post.getStatus())) {
            if (post.getBoardId() != null) {
                CountUtils.increment(boardService, Board::getId, post.getBoardId(), "postCount", -1);
            }
            innerUserService.incrementUserCount(post.getUserId(), "postCount", -1);
        }

        // 逻辑删除同步 ES：从索引删除
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_POST_INDEX,
                new PostIndexMessage(id, PostIndexMessage.ACTION_DELETE));
    }

    /**
     * 发布草稿：按草稿的 draftOfId 决定去向——编辑草稿更新原帖送审、新建草稿创建送审，
     * 成功后删除草稿记录。发布内容以本次请求为准（草稿槽位内容可能滞后于前端编辑）。
     *
     * @return 目标帖子 id（字符串）：编辑草稿返回原帖 id，新建草稿返回新帖 id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<String> publishDraft(Long draftId, PostDTO dto) {
        long loginId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(draftId == null || BeanUtil.isEmpty(dto), ErrorCode.PARAMS_ERROR, "参数不能为空");

        Post draft = lambdaQuery()
                .eq(Post::getId, draftId)
                .eq(Post::getUserId, loginId)
                .eq(Post::getStatus, PostStatusEnum.DRAFT.getCode())
                .one();
        ThrowUtils.throwIf(BeanUtil.isEmpty(draft), ErrorCode.NOT_FOUND_ERROR, "草稿不存在");

        // 发布内容由本次请求为准，且强制走送审：status 置空，避免作者借草稿字段绕过审核
        dto.setStatus(null);

        String targetId;
        if (draft.getDraftOfId() != null) {
            // 编辑草稿 → 应用内容到原帖（先审后发）
            dto.setId(draft.getDraftOfId());
            if (StrUtil.isBlank(dto.getPostCode())) {
                Post original = getById(draft.getDraftOfId());
                ThrowUtils.throwIf(BeanUtil.isEmpty(original), ErrorCode.NOT_FOUND_ERROR, "原帖不存在");
                dto.setPostCode(original.getPostCode());
            }
            updatePost(dto);
            targetId = StrUtil.toString(draft.getDraftOfId());
        } else {
            // 新建草稿 → 创建送审（标题重复/内容非空等校验在 createPost 内）
            PostVO vo = createPost(dto);
            targetId = StrUtil.toString(vo.getId());
        }

        deleteDraftRecord(draftId);
        return ResultUtils.success(targetId);
    }

    /**
     * 删除草稿（作者本人）：逻辑删除草稿记录，并清理图片/标签关联（标签 useCount 回退）。
     * 草稿未参与板块/作者计数，无需回退计数。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        long loginId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "草稿 id 不能为空");

        Post draft = lambdaQuery()
                .eq(Post::getId, id)
                .eq(Post::getUserId, loginId)
                .eq(Post::getStatus, PostStatusEnum.DRAFT.getCode())
                .one();
        ThrowUtils.throwIf(BeanUtil.isEmpty(draft), ErrorCode.NOT_FOUND_ERROR, "草稿不存在");

        deleteDraftRecord(id);
    }

    /** 草稿记录清理（逻辑删除 + 图片/标签关联物理删 + 标签 useCount 回退） */
    private void deleteDraftRecord(Long draftId) {
        boolean removed = removeById(draftId);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "草稿删除失败");

        postImageService.remove(new LambdaQueryWrapper<PostImage>().eq(PostImage::getPostId, draftId));
        List<PostTag> postTags = postTagService.lambdaQuery()
                .eq(PostTag::getPostId, draftId)
                .list();
        for (PostTag pt : postTags) {
            tagService.lambdaUpdate().eq(Tag::getId, pt.getTagId())
                    .setSql("useCount = useCount - 1").update();
        }
        postTagService.remove(new LambdaQueryWrapper<PostTag>().eq(PostTag::getPostId, draftId));
    }

    /**
     * 管理员审核帖子（推进状态 + 迁移「审核中」版本的图片/标签）。
     *
     * <p>「先审后发」语义下，待审内容在 createPost / updatePost 时<b>已经直接写入正式字段</b>
     * （同时把帖子置为「审核中」，审核期间前台不展示），因此审核环节不存在需要"应用"的影子内容，
     * 只需推进状态、并把 status=审核中 的图片/标签整体迁移为最终状态。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditPost(Long id, Boolean pass,String message) {
        ThrowUtils.throwIf(id == null || pass == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        Post post = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // 仅「审核中」的帖子需要审核：避免重复审核，也避免误操作草稿/已发布/已下架的帖子
        ThrowUtils.throwIf(!PostStatusEnum.REVIEWING.matches(post.getStatus()),
                ErrorCode.OPERATION_ERROR,
                "该帖子当前不处于「" + PostStatusEnum.REVIEWING.getText() + "」，无需审核");

        // 通过 → 已发布；
        // 驳回 → 下架：内容已覆盖正式字段，没有旧版本可回退，不能对外展示（作者可修改后重新提交）
        PostStatusEnum finalStatus = pass ? PostStatusEnum.PUBLISHED : PostStatusEnum.OFFLINE;
        PostAuditStatusEnum auditResult = pass ? PostAuditStatusEnum.APPROVED : PostAuditStatusEnum.REJECTED;

        // 带上 status=审核中 作为更新条件：并发重复审核时只有一次能生效
        boolean updated = lambdaUpdate()
                .eq(Post::getId, id)
                .eq(Post::getStatus, PostStatusEnum.REVIEWING.getCode())
                .set(Post::getStatus, finalStatus.getCode())
                .set(Post::getAuditStatus, auditResult.getCode())
                .update();
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "审核操作失败，请刷新后重试");

        // 计数口径：仅「审核通过」（审核中→已发布）才 +1；驳回（→下架）不计数也不回收。
        // 注意必须用 post.getUserId()——操作者是管理员，StpUtil 取到的是管理员的 id
        if (pass) {
            if (post.getBoardId() != null) {
                CountUtils.increment(boardService, Board::getId, post.getBoardId(), "postCount", 1);
            }
            innerUserService.incrementUserCount(post.getUserId(), "postCount", 1);
        }

        // 审核日志落地：记录本次审核的管理员、目标帖子、动作（通过/下架）、说明（与审核状态更新同事务）
        saveAuditLog(post.getId(), pass, message);

        // 「审核中」版本的图片/标签整体迁移为最终状态，并清理该帖其它历史版本（标签同步回退 useCount）
        migrateReviewingRelations(id, finalStatus.getCode());
        // 审核通过：通知该帖子作者的粉丝（发布推送）。
        // 粉丝列表在事务内查好放进消息（消费方零查询），经 evt.post 由 notify 服务异步逐条发通知
        if (pass) {
            List<Long> fans = innerFollowService.listFanIds(post.getUserId());
            if (!fans.isEmpty()) {
                rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                        ForumMqConstants.RK_EVT_POST,
                        new PostEventMessage(post.getUserId(), id, fans));
            }
        } else {
            // 驳回：经 evt.admin 由 notify 服务通知作者审核结果
            rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                    ForumMqConstants.RK_EVT_ADMIN,
                    new AdminEventMessage(id, post.getUserId(), message));
        }
        // 审核结果同步 ES：通过且公开→索引，驳回/私密→删除
        syncPostToEs(id);
    }

    /**
     * 查看草稿箱：当前登录用户 status=草稿 的帖子列表（按创建时间倒序）。
     * 作者取登录态，不信任前端传 userId；图片按草稿版本回读。
     */
    @Override
    public List<PostVO> getDraftList() {
        long loginId = StpUtil.getLoginIdAsLong();
        List<Post> drafts = lambdaQuery()
                .eq(Post::getUserId, loginId)
                .eq(Post::getStatus, PostStatusEnum.DRAFT.getCode())
                .orderByDesc(Post::getCreatedAt)
                .list();
        return drafts.stream()
                .map(this::buildPostVO)
                .toList();
    }

    /**
     * 帖子分页查询（可查自己/别人；查他人只展示已发布）。
     * 返回列表专用 VO（PostBrowseVO），作者信息批量装配避免 N+1。
     */
    @Override
    public IPage<PostBrowseVO> listPosts(PostQueryDTO dto) {
        // 这里用 isLogin 守卫，未登录视为 null，isSelf 自然为 false，只返回「已发布」内容
        Long loginId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto), ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        QueryWrapper<Post> queryWrapper = QueryWrapperUtils.getPostQueryWrapper(dto);

        boolean isSelf = dto.getUserId() != null && Objects.equals(dto.getUserId(), loginId);
        queryWrapper.eq("status", PostStatusEnum.PUBLISHED.getCode());
        Page<Post> page = this.page(new Page<>(dto.getCurrent(), dto.getPageSize()), queryWrapper);

        // 批量查作者（user 表），按内部 id 建索引；避免逐条查询造成 N+1
        List<Long> authorIds = page.getRecords().stream()
                .map(Post::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> userMap = authorIds.isEmpty() ? Map.of()
                : innerUserService.listByIds(authorIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        // 装配列表 VO（轻量字段，不含正文/图片全列表，点进详情走 getPostDetail）
        List<PostBrowseVO> voList = page.getRecords().stream()
                .map(post -> buildPostBrowseVO(post, userMap))
                .toList();
        // 批量填充板块对象（无板块帖自动跳过，防 N+1）
        boardBriefFiller.fillBoardBrief(voList);
        // 批量填充话题标签（post_tag + tag 两表各查一次，防 N+1）
        tagBriefFiller.fillTags(voList);
        // 当前登录用户批量装配 isLiked / isCollected（未登录跳过）
        fillIsLiked(voList);
        fillIsCollected(voList);
        IPage<PostBrowseVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(voList);
        return result;
    }

    /**
     * 帖子详情查询（点进帖子后展示完整内容）。
     * 作者本人可看自己任意状态（草稿/审核中/下架）；非作者仅可看「已发布」，
     * 其余状态一律按「帖子不存在」处理（与列表可见性规则一致，不泄露内容存在性）。
     */
    @Override
    public PostVO getPostDetail(Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        Post post = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 公开接口（@SaIgnore）：游客未登录时按 null 处理，非作者仅可看「已发布」，与列表规则一致
        Long loginId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        // 非作者和非管理员只能看「已发布」：草稿/审核中/下架一律按「帖子不存在」处理，与列表可见性规则一致
        // （isAdmin 归 user 服务经 Dubbo 判定；游客 loginId=null 时直接按非管理员处理，不发起远程调用）
        boolean notVisible = !Objects.equals(post.getUserId(), loginId)
                && !PostStatusEnum.PUBLISHED.matches(post.getStatus())
                && !(loginId != null && innerUserService.isAdmin(loginId));
        ThrowUtils.throwIf(notVisible, ErrorCode.NOT_FOUND_ERROR, "帖子不存在或未发布");
        Long userId = post.getUserId();
        User user = innerUserService.getById(userId);
        PostVO postVO = buildPostVO(post);
        postVO.setUserNickname(user.getNickname());
        postVO.setUserAvatar(user.getAvatar());
        return postVO;
    }

    /**
     * 关注流：我关注的人的帖子（仅已发布 + 审核通过 + 公开/仅粉丝可见）。
     *
     * <p>两步查询：① user_follow 查我关注的人（followerId=登录用户、status=1，取 followeeId 内部 id）；
     * ② post 表 in(userId=关注者) + 状态/审核/可见性过滤，按创建时间倒序。作者信息批量装配。</p>
     */
    @Override
    public IPage<PostBrowseVO> listFollowPosts(long current, long pageSize) {
        long loginId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(current < 1 || pageSize < 1, ErrorCode.PARAMS_ERROR, "分页参数不合法");

        // 1. 我关注的人（user_follow 统一存内部 id，与关注模块约定一致）
        List<Long> followeeIds = innerFollowService.listFanIds(loginId);
        // 未关注任何人 → 直接返回空页
        if (followeeIds.isEmpty()) {
            IPage<PostBrowseVO> empty = new Page<>(current, pageSize);
            empty.setRecords(List.of());
            return empty;
        }

        // 2. 关注者的帖子：已发布 + 审核通过 + 可见性∈{公开, 仅粉丝可见}，最新在前
        Page<Post> page = lambdaQuery()
                .in(Post::getUserId, followeeIds)
                .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .eq(Post::getAuditStatus, PostAuditStatusEnum.APPROVED.getCode())
                .in(Post::getVisibility,
                        PostVisibilityEnum.PUBLIC.getCode(), PostVisibilityEnum.FANS_ONLY.getCode())
                .orderByDesc(Post::getCreatedAt)
                .page(new Page<>(current, pageSize));

        // 3. 批量查作者 + 装配列表 VO（与 listPosts 相同模式，避免 N+1）
        List<Long> authorIds = page.getRecords().stream()
                .map(Post::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> userMap = authorIds.isEmpty() ? Map.of()
                : innerUserService.listByIds(authorIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<PostBrowseVO> voList = page.getRecords().stream()
                .map(post -> buildPostBrowseVO(post, userMap))
                .toList();
        // 批量填充板块对象（无板块帖自动跳过，防 N+1）
        boardBriefFiller.fillBoardBrief(voList);
        // 批量填充话题标签（post_tag + tag 两表各查一次，防 N+1）
        tagBriefFiller.fillTags(voList);
        // 当前登录用户批量装配 isLiked / isCollected
        fillIsLiked(voList);
        fillIsCollected(voList);
        IPage<PostBrowseVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(voList);
        return result;
    }

    /**
     * 管理员帖子列表（统一入口：已发布/草稿/审核中/下架 全状态可查）。
     * <p>status 由 {@code PostQueryDTO} 传入（中文文字→枚举码精确匹配）：
     * <ul>
     *   <li><b>status 为空</b> → 查询全部状态（不做过滤）；</li>
     *   <li><b>status 非空</b> → 按传入状态筛选（已发布/草稿/审核中/下架）。</li>
     * </ul>
     * 条件与用户列表一致（模糊/精确过滤），默认按创建时间倒序；管理端不做可见性过滤。
     * 图片按帖子当前状态版本回读（loadImageUrls 使用 post.getStatus()）。</p>
     */
    @Override
    public IPage<PostVO> listAdminPosts(PostQueryDTO dto) {
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto), ErrorCode.PARAMS_ERROR, "请求参数不能为空");

        // status 的筛选完全交给 getPostQueryWrapper：传了按状态精确匹配，不传则不附加条件（查全部）
        QueryWrapper<Post> queryWrapper = QueryWrapperUtils.getPostQueryWrapper(dto);
        Page<Post> page = this.page(new Page<>(dto.getCurrent(), dto.getPageSize()), queryWrapper);

        // 图片按帖子当前状态版本回读（loadImageUrls 使用 post.getStatus()）
        List<PostVO> voList = page.getRecords().stream()
                .map(this::buildPostVO)
                .toList();
        IPage<PostVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(voList);
        return result;
    }

    /**
     * 管理员自由设置帖子状态（status / visibility，传哪个改哪个，至少传一个）。
     *
     * <p><b>status 变化时的联动</b>（保持与审核流口径一致）：</p>
     * <ul>
     *   <li><b>计数</b>：user.postCount / board.postCount 按「仅已发布」口径——非发布→已发布 +1，
     *       已发布→非发布 -1；其余状态互转不调整（用 post.getUserId()，操作者是管理员）；</li>
     *   <li><b>auditStatus</b>：同步映射——已发布→通过、下架→驳回、草稿/审核中→待审；</li>
     *   <li><b>图片/标签版本</b>：以变更前状态版本为「当前内容」迁移到新状态，清理其余历史版本
     *       （标签回退 useCount），复用 {@link #migratePostRelations}。</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminSetPostStatus(AdminPostStatusDTO dto) {
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto) || dto.getPostId() == null,
                ErrorCode.PARAMS_ERROR, "帖子 id 不能为空");
        ThrowUtils.throwIf(dto.getStatus() == null && dto.getVisibility() == null,
                ErrorCode.PARAMS_ERROR, "status 和 visibility 至少传一个");

        // 枚举合法性校验（null 表示不修改该字段）
        ThrowUtils.throwIf(dto.getStatus() != null
                        && PostStatusEnum.getByCode(dto.getStatus()) == null,
                ErrorCode.PARAMS_ERROR, "非法的状态码：" + dto.getStatus());
        ThrowUtils.throwIf(dto.getVisibility() != null
                        && PostVisibilityEnum.getByCode(dto.getVisibility()) == null,
                ErrorCode.PARAMS_ERROR, "非法的可见性码：" + dto.getVisibility());

        Post post = getById(dto.getPostId());
        ThrowUtils.throwIf(BeanUtil.isEmpty(post), ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 幂等：传入字段均与现值相同 → 无需更新
        boolean statusChanged = dto.getStatus() != null && !dto.getStatus().equals(post.getStatus());
        boolean visibilityChanged = dto.getVisibility() != null && !dto.getVisibility().equals(post.getVisibility());
        if (!statusChanged && !visibilityChanged) {
            return;
        }

        // 计数联动：仅当 status 跨过「已发布」边界时调整 user/board postCount
        if (statusChanged) {
            boolean wasPublished = PostStatusEnum.PUBLISHED.matches(post.getStatus());
            boolean nowPublished = PostStatusEnum.PUBLISHED.matches(dto.getStatus());
            if (wasPublished && !nowPublished) {
                // 已发布 → 非已发布：回收计数
                if (post.getBoardId() != null) {
                    CountUtils.increment(boardService, Board::getId, post.getBoardId(), "postCount", -1);
                }
                innerUserService.incrementUserCount(post.getUserId(), "postCount", -1);
            } else if (!wasPublished && nowPublished) {
                // 非已发布 → 已发布：增加计数
                if (post.getBoardId() != null) {
                    CountUtils.increment(boardService, Board::getId, post.getBoardId(), "postCount", 1);
                }
                innerUserService.incrementUserCount(post.getUserId(), "postCount", 1);
            }
        }

        // 更新帖子：status / visibility / auditStatus（status 变化时同步映射）
        LambdaUpdateWrapper<Post> uw = new LambdaUpdateWrapper<>();
        uw.eq(Post::getId, post.getId());
        uw.set(statusChanged, Post::getStatus, dto.getStatus());
        uw.set(visibilityChanged, Post::getVisibility, dto.getVisibility());
        if (statusChanged) {
            uw.set(Post::getAuditStatus, mapAuditStatusByStatus(dto.getStatus()));
        }
        boolean updated = update(uw);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "设置失败");

        // 图片/标签版本迁移：以变更前状态版本为「当前内容」迁移到新状态，清理其余历史版本
        if (statusChanged) {
            migratePostRelations(post.getId(), post.getStatus(), dto.getStatus());
        }
        // 状态/可见性变更同步 ES：恢复发布→索引，下架/私密→删除
        syncPostToEs(post.getId());
    }

    /**
     * status 枚举码 → auditStatus 枚举码 的同步映射：
     * 已发布→通过(2)、下架→驳回(3)、草稿/审核中→待审(1)。
     */
    private Integer mapAuditStatusByStatus(Integer statusCode) {
        if (PostStatusEnum.PUBLISHED.matches(statusCode)) {
            return PostAuditStatusEnum.APPROVED.getCode();
        }
        if (PostStatusEnum.OFFLINE.matches(statusCode)) {
            return PostAuditStatusEnum.REJECTED.getCode();
        }
        return PostAuditStatusEnum.PENDING.getCode();
    }


    // ==================== 私有工具方法 ====================

    /**
     * 实体 → 对外展示 VO。
     *
     * <p>三个枚举字段（visibility / status / auditStatus）在实体里是整数、在 VO 里是文字，
     * 类型不一致，必须从 {@code copyProperties} 中排除后手工转换，否则 Hutool 会把
     * 整数直接转成 "1"/"3" 这类无意义字符串；topic 由逗号串解析为 TagVO 列表；
     * imageUrl 从 post_image 表按<b>帖子当前状态</b>回读（版本化存储，见类头注释）。</p>
     *
     * @param post 帖子实体（null 返回 null）
     * @return 装配好的 VO
     */
    private PostVO buildPostVO(Post post) {
        if (post == null) {
            return null;
        }
        Long userId = post.getUserId();
        User user = innerUserService.getById(userId);

        PostVO vo = BeanUtil.copyProperties(post, PostVO.class,
                "topic", "visibility", "status", "auditStatus");
        vo.setTopic(parseTopicTags(post.getTopic()));
        vo.setVisibility(PostVisibilityEnum.textOfCode(post.getVisibility()));
        vo.setStatus(PostStatusEnum.textOfCode(post.getStatus()));
        vo.setAuditStatus(PostAuditStatusEnum.textOfCode(post.getAuditStatus()));
        List<String> imageUrls = loadImageUrls(post.getId(), post.getStatus());
        vo.setImageUrl(imageUrls);
        vo.setContentBlocks(buildContentBlocks(post, imageUrls));
        vo.setUserNickname(user.getNickname());

        // 板块简要信息：boardId 非空时单查一次 board（详情/草稿均为单帖场景，无 N+1；
        // 板块已逻辑删除时 getById 返回 null，BoardBrief.of(null) 返回 null，前端不渲染板块入口）
        if (post.getBoardId() != null) {
            vo.setBoard(PostBrowseVO.BoardBrief.of(boardService.getById(post.getBoardId())));
        }
        return vo;
    }

    /**
     * 装配结构化内容块（图文混排）。
     *
     * <p>新数据：{@code post.content} 为 ContentBlock 的 JSON 数组，直接解析；
     * 旧数据：{@code post.content} 为纯文本 + {@code imageUrls} 图集，合成等价 blocks
     * （一个 p 块 + 多个 image 块），保证前端统一消费 contentBlocks 即可正确渲染。</p>
     *
     * @param post      帖子实体
     * @param imageUrls 已回读的本版本图片 URL（旧数据图集，新数据为空）
     * @return 内容块列表（无则空列表，绝不返回 null）
     */
    private List<ContentBlock> buildContentBlocks(Post post, List<String> imageUrls) {
        String content = post.getContent();
        // 新数据：content 是以 '[' 开头的 JSON 数组，尝试解析为 blocks
        if (StrUtil.isNotBlank(content) && content.trim().startsWith("[")) {
            try {
                return JSONUtil.toList(JSONUtil.parseArray(content), ContentBlock.class);
            } catch (Exception ignored) {
                // 解析失败（脏数据），fallback 到下方纯文本合成
            }
        }
        // 旧数据兼容：纯文本 → 单个 p 块；imageUrls 图集 → image 块
        List<ContentBlock> blocks = new ArrayList<>();
        if (StrUtil.isNotBlank(content)) {
            ContentBlock p = new ContentBlock();
            p.setType("p");
            p.setText(content);
            blocks.add(p);
        }
        if (imageUrls != null) {
            for (String url : imageUrls) {
                ContentBlock img = new ContentBlock();
                img.setType("image");
                img.setUrl(url);
                blocks.add(img);
            }
        }
        return blocks;
    }

    /**
     * 实体 → 列表 VO（PostBrowseVO）。
     *
     * <p>仅装配卡片渲染所需轻量字段；作者昵称/头像取自调用方批量查询的 {@code userMap}
     * （查不到作者时置空，不抛错）。<b>不查 post_image</b>（列表只用 cover 封面）、
     * <b>不解析 topic、不回显枚举文字</b> —— 正文、图片全列表、话题、可见性/状态等详情信息
     * 由 {@code getPostDetail} 返回的 {@link PostVO} 提供。</p>
     *
     * @param post    帖子实体（null 返回 null）
     * @param userMap 批量查询的作者索引（内部 id → User），可空
     * @return 列表 VO
     */
    private PostBrowseVO buildPostBrowseVO(Post post, Map<Long, User> userMap) {
        if (post == null) {
            return null;
        }
        PostBrowseVO vo = BeanUtil.copyProperties(post, PostBrowseVO.class);
        vo.setContentPreview(buildContentPreview(post.getContent()));
        User author = userMap == null ? null : userMap.get(post.getUserId());
        if (author != null) {
            vo.setUserNickname(author.getNickname());
            vo.setUserAvatar(author.getAvatar());
        }
        return vo;
    }

    /**
     * 按当前登录用户批量填充列表 VO 的 {@code isLiked}（对齐 11-like-module.md §13 末段设计）。
     *
     * <p>isLiked 是用户维度动态状态，不冗余进 post 表 / ES 索引（会串号）。点赞归 interaction 服务，
     * 本方法经 Dubbo 调 {@code InnerInteractionService.getPostLikedMap} 批量装配；
     * 未登录（游客浏览公开列表）跳过，VO.isLiked 保持 null，前端按未赞渲染。</p>
     *
     * @param voList 本页已组装的 PostBrowseVO 列表（原地填充，不新建）
     */
    private void fillIsLiked(List<PostBrowseVO> voList) {
        if (voList == null || voList.isEmpty() || !StpUtil.isLogin()) {
            return;
        }
        long loginId = StpUtil.getLoginIdAsLong();
        List<Long> postIds = voList.stream()
                .map(PostBrowseVO::getId)
                .filter(Objects::nonNull)
                .toList();
        if (postIds.isEmpty()) {
            return;
        }
        Map<Long, Boolean> likedMap = innerInteractionService.getPostLikedMap(loginId, postIds);
        voList.forEach(vo -> {
            if (vo.getId() != null) {
                vo.setIsLiked(likedMap.getOrDefault(vo.getId(), false));
            }
        });
    }

    /**
     * 抽取正文纯文本并截断为列表卡片预览摘要。
     *
     * <p>新数据 {@code content} 为 ContentBlock 的 JSON 数组，拼接各文本块（p / heading）的
     * {@code text}；旧数据为纯文本，直接使用。统一截断到 {@value #PREVIEW_MAX_LENGTH} 字符
     * （超出追加省略号），无文本返回 null（前端可降级显示封面图）。</p>
     *
     * @param content 帖子正文字段（JSON 块数组或纯文本，可空）
     * @return 预览正文（无内容返回 null）
     */
    private String buildContentPreview(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        String plain = content.trim();
        // 新数据：content 是以 '[' 开头的 JSON 数组，提取各文本块 text 拼接
        if (plain.startsWith("[")) {
            try {
                List<ContentBlock> blocks = JSONUtil.toList(JSONUtil.parseArray(content), ContentBlock.class);
                StringBuilder sb = new StringBuilder();
                for (ContentBlock b : blocks) {
                    if (StrUtil.isNotBlank(b.getText())) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(b.getText().trim());
                    }
                }
                plain = sb.toString();
            } catch (Exception ignored) {
                // 解析失败（脏数据），fallback 到下方纯文本截断
            }
        }
        if (StrUtil.isBlank(plain)) {
            return null;
        }
        // 截断：超过 PREVIEW_MAX_LENGTH 字符时追加省略号
        if (plain.length() > PREVIEW_MAX_LENGTH) {
            return StrUtil.sub(plain, 0, PREVIEW_MAX_LENGTH) + "...";
        }
        return plain;
    }

    /**
     * 解析 post.topic（逗号分隔的 tag id 串）为 {@link TagVO} 列表。
     *
     * <p>先拆出合法 Long id（非数字片段跳过，兼容历史脏数据），再批量查 tag 表装配 id+name，
     * 并保持原串中的 id 顺序；已禁用/已删除的 tag 不出现在结果中。</p>
     *
     * @param topic 实体中的逗号分隔 tag id 串（可能为 null/空）
     * @return 结构化标签列表（无则空列表，绝不返回 null）
     */
    private List<TagVO> parseTopicTags(String topic) {
        if (StrUtil.isBlank(topic)) {
            return List.of();
        }
        List<Long> ids = StrUtil.split(topic, ',').stream()
                .map(StrUtil::trim)
                .filter(NumberUtil::isLong)
                .map(Long::valueOf)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        // 批量查 tag 表（含已禁用的，避免历史帖子的标签被静默丢弃），按 id 建索引
        List<Tag> tags = tagService.lambdaQuery()
                .in(Tag::getId, ids)
                .list();
        Map<Long, Tag> tagMap = tags.stream()
                .collect(Collectors.toMap(Tag::getId, t -> t, (a, b) -> a));
        // 按原串 id 顺序装配 TagVO（跳过查不到的）
        return ids.stream()
                .map(tagMap::get)
                .filter(Objects::nonNull)
                .map(t -> {
                    TagVO vo = new TagVO();
                    vo.setId(t.getId());
                    vo.setName(t.getName());
                    return vo;
                })
                .toList();
    }

    /**
     * 回读指定版本状态下的图片 URL（按 sort 升序）。
     *
     * @param postId 帖子内部 id
     * @param status 版本状态（已发布 / 草稿 / 审核中），与帖子当前 status 一致
     */
    private List<String> loadImageUrls(Long postId, Integer status) {
        if (postId == null || status == null) {
            return List.of();
        }
        return postImageService.lambdaQuery()
                .eq(PostImage::getPostId, postId)
                .eq(PostImage::getStatus, status)
                .orderByAsc(PostImage::getSort)
                .list().stream()
                .map(PostImage::getUrl)
                .collect(Collectors.toList());
    }

    /**
     * 解析并脱敏正文：blocks 优先（序列化为 JSON，文本块逐块脱敏），无 blocks 时退回纯文本。
     * 既无 blocks 又无纯文本时返回 null（编辑场景下表示「不更新正文」）。
     */
    private String resolveContent(PostDTO dto) {
        List<ContentBlock> blocks = dto.getContentBlocks();
        if (blocks != null && !blocks.isEmpty()) {
            List<ContentBlock> cleaned = new ArrayList<>();
            for (ContentBlock b : blocks) {
                ContentBlock c = new ContentBlock();
                c.setType(b.getType());
                c.setUrl(b.getUrl());
                c.setW(b.getW());
                c.setH(b.getH());
                c.setAlt(b.getAlt());
                if (StrUtil.isNotBlank(b.getText())) {
                    c.setText(scrub(b.getText(), "正文"));
                }
                cleaned.add(c);
            }
            return JSONUtil.toJsonStr(cleaned);
        }
        if (StrUtil.isBlank(dto.getContent())) {
            return null;
        }
        return scrub(dto.getContent(), "正文");
    }

    /**
     * 敏感词扫描与处置（对齐 BoardServiceImpl.scrubText）：
     * 命中拦截词直接拒绝；命中替换词脱敏为 ***；审核词/放行保留原文。
     */
    private String scrub(String text, String fieldName) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        SensitiveWordFilter.FilterResult fr = sensitiveWordFilter.filter(text);
        ThrowUtils.throwIf(fr.action == SensitiveWordFilter.SensitiveAction.INTERCEPT,
                ErrorCode.PARAMS_ERROR, fieldName + "包含敏感或违规内容，请修改后重试");
        if (fr.action == SensitiveWordFilter.SensitiveAction.REPLACED) {
            return fr.processedText;
        }
        return text;
    }

    /**
     * 生成对外编码 postCode：Redis 原子自增 + "P" 前缀。
     * 计数器不存在时以基准 100000 初始化（setIfAbsent 仅一次），与 userId 生成方式对齐。
     */
    private String generatePostCode() {
        if (!stringRedisTemplate.hasKey(POST_CODE_COUNTER_KEY)) {
            stringRedisTemplate.opsForValue().setIfAbsent(POST_CODE_COUNTER_KEY, String.valueOf(POST_CODE_BASE));
        }
        long n = stringRedisTemplate.opsForValue().increment(POST_CODE_COUNTER_KEY);
        return "P" + n;
    }

    /**
     * 全量写入图片（按列表顺序 sort 递增；空列表/空 URL 跳过）。
     *
     * @param status 该批图片所属的帖子版本状态（已发布 / 草稿 / 审核中），与写入时帖子的目标状态一致
     */
    private void saveImages(Long postId, List<String> images, Integer status) {
        if (images == null || images.isEmpty()) {
            return;
        }
        List<PostImage> list = new ArrayList<>();
        int sort = 0;
        for (String url : images) {
            if (StrUtil.isBlank(url)) {
                continue;
            }
            PostImage pi = new PostImage();
            pi.setPostId(postId);
            pi.setUrl(url);
            pi.setSort(sort++);
            pi.setStatus(status);
            list.add(pi);
        }
        if (!list.isEmpty()) {
            postImageService.saveBatch(list);
        }
    }

    /**
     * 重建<b>指定状态下</b>的图片：先查该帖子在该状态下是否已有记录，
     * 没有则直接插入新的；已有则同状态删旧插新（全量替换），<b>其它状态的图片不受影响</b>。
     *
     * @param status 目标版本状态（已发布 / 草稿 / 审核中）
     */
    private void rebuildImages(Long postId, List<String> images, Integer status) {
        // 先查看该帖子在该状态下有没有图片
        List<PostImage> exist = postImageService.lambdaQuery()
                .eq(PostImage::getPostId, postId)
                .eq(PostImage::getStatus, status)
                .list();
        if (exist.isEmpty()) {
            // 没有该状态的记录 → 直接插入新的
            saveImages(postId, images, status);
            return;
        }
        // 已存在该状态的记录 → 仅删除同状态旧记录，再插入新的
        postImageService.remove(new LambdaQueryWrapper<PostImage>()
                .eq(PostImage::getPostId, postId)
                .eq(PostImage::getStatus, status));
        saveImages(postId, images, status);
    }

    /**
     * 校验并解析标签：前端传 tag id 列表 → 查 tag 表（<b>仅 status=1 未被禁用</b>）。
     * 传入了不存在或被禁用的 id 时抛参数错误，防止引用失效/违规标签。
     *
     * @param tagIds 前端传入的 tag id 列表（可为 null/空）
     * @return 解析后的有效标签列表（按传入顺序）
     */
    private List<Tag> resolveTags(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = tagIds.stream().distinct().toList();
        List<Tag> tags = tagService.lambdaQuery()
                .in(Tag::getId, distinctIds)
                .eq(Tag::getStatus, 1)   // 仅未被禁用的标签
                .list();
        ThrowUtils.throwIf(tags.size() != distinctIds.size(),
                ErrorCode.PARAMS_ERROR, "包含无效或已禁用的标签");
        return tags;
    }

    /**
     * 标签名称列表转存储id字符
     */
    private String joinTagNames(List<Tag> tags) {
        return tags.stream().map(tag -> StrUtil.toString(tag.getId())).collect(Collectors.joining(","));
    }

    /**
     * 绑定标签关联：useCount+1 + 写 post_tag（唯一键 (postId,tagId,status) 防重）。
     *
     * @param status 该批关联所属的帖子版本状态（已发布 / 草稿 / 审核中）
     */
    private void bindTags(Long postId, List<Tag> tags, Integer status) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (Tag tag : tags) {
            PostTag pt = new PostTag();
            pt.setPostId(postId);
            pt.setTagId(tag.getId());
            pt.setStatus(status);
            try {
                postTagService.save(pt);
                // 仅真正新增关联时才累加使用次数，避免重复关联把 useCount 刷高
                tagService.lambdaUpdate().eq(Tag::getId, tag.getId())
                        .setSql("useCount = useCount + 1").update();
            } catch (DuplicateKeyException e) {
                // 同状态下已关联过，忽略（useCount 也不重复累加）
            }
        }
    }

    /**
     * 重建<b>指定状态下</b>的标签关联：先查该帖子在该状态下是否已有关联，
     * 没有则直接绑定新的；已有则同状态删旧（useCount 回退）再绑新，<b>其它状态的关联不受影响</b>。
     *
     * @param status 目标版本状态（已发布 / 草稿 / 审核中）
     */
    private void rebuildTags(Long postId, List<Tag> tags, Integer status) {
        // 先查看该帖子在该状态下有没有标签关联
        List<PostTag> old = postTagService.lambdaQuery()
                .eq(PostTag::getPostId, postId)
                .eq(PostTag::getStatus, status)
                .list();
        if (old.isEmpty()) {
            // 没有该状态的关联 → 直接绑定新的
            bindTags(postId, tags, status);
            return;
        }
        // 已存在该状态的关联 → 旧标签 useCount 回退后删除同状态关联，再绑新
        for (PostTag pt : old) {
            tagService.lambdaUpdate().eq(Tag::getId, pt.getTagId())
                    .setSql("useCount = useCount - 1").update();
        }
        postTagService.remove(new LambdaQueryWrapper<PostTag>()
                .eq(PostTag::getPostId, postId)
                .eq(PostTag::getStatus, status));
        bindTags(postId, tags, status);
    }

    /**
     * 审核日志落地：记录管理员对目标帖子的审核动作。
     *
     * <p>字段语义（对齐 auditLog 表）：targetType=1 帖子；action 1通过 2下架 3删除——
     * 审核流只有「通过」与「驳回(下架)」两种，故 action 取 1/2；remark 存拒绝原因/说明。
     * 管理员 id 取当前登录态（Controller 已用 @SaCheckRole("admin") 保证），
     * 与审核状态更新在同一事务内，任一失败整体回滚。</p>
     */
    private void saveAuditLog(Long postId, boolean pass, String remark) {
        Auditlog log = new Auditlog();
        log.setAdminId(StpUtil.getLoginIdAsLong());
        log.setTargetType(1);
        log.setTargetId(postId);
        log.setAction(pass ? 1 : 2);
        log.setRemark(remark);
        log.setCreatedAt(new Date());
        auditlogService.save(log);
    }

    /**
     * 审核结束后迁移关联数据：把该帖「审核中」版本的图片/标签整体迁移为最终状态。
     *
     * <p>迁移前会清掉该帖<b>除「审核中」以外的所有历史版本</b>（标签同步回退 useCount）：
     * 「先审后发」下正式字段已被新内容覆盖，旧版本的图片/标签不再对应任何内容，
     * 若只清最终状态那一份，驳回（审核中→下架）时旧「已发布版」就会残留成孤儿数据并长期占着 useCount。
     * 清理后可保证：审核结束时该帖只剩一套与帖子状态一致的图片/标签。</p>
     *
     * @param postId      帖子内部 id
     * @param finalStatus 审核后的最终状态（通过=已发布；驳回=下架）
     */
    private void migrateReviewingRelations(Long postId, Integer finalStatus) {
        migratePostRelations(postId, PostStatusEnum.REVIEWING.getCode(), finalStatus);
    }

    /**
     * 迁移帖子图片/标签的版本状态：以 sourceStatus 版本为「当前内容」整体迁移为 finalStatus，
     * 并清掉该帖<b>除 sourceStatus 以外的所有历史版本</b>（标签同步回退 useCount）。
     *
     * <p>版本化存储下（post_image.status / post_tag.status 记录行属于帖子的哪个生命周期版本），
     * 状态流转时旧版本的图片/标签不再对应任何内容：若只清目标状态那一份，
     * 其它版本会残留成孤儿数据并长期占着 useCount。清理后可保证：
     * 迁移结束时该帖只剩一套与帖子状态一致的图片/标签。</p>
     *
     * @param postId       帖子内部 id
     * @param sourceStatus 当前内容所在版本（迁移源：审核流为「审核中」，管理员强制设置时为变更前状态）
     * @param finalStatus  目标状态（迁移后归属）
     */
    private void migratePostRelations(Long postId, Integer sourceStatus, Integer finalStatus) {
        if (Objects.equals(sourceStatus, finalStatus)) {
            return;
        }
        // 1. 图片：清掉其它版本（含 status 为 null 的历史脏数据），再把 source 版本迁移过去
        postImageService.remove(new LambdaQueryWrapper<PostImage>()
                .eq(PostImage::getPostId, postId)
                .and(w -> w.ne(PostImage::getStatus, sourceStatus).or().isNull(PostImage::getStatus)));
        postImageService.lambdaUpdate()
                .eq(PostImage::getPostId, postId)
                .eq(PostImage::getStatus, sourceStatus)
                .set(PostImage::getStatus, finalStatus)
                .update();

        // 2. 标签：其它版本先回退 useCount 再删除，最后把 source 版本迁移过去
        List<PostTag> staleTags = postTagService.lambdaQuery()
                .eq(PostTag::getPostId, postId)
                .and(w -> w.ne(PostTag::getStatus, sourceStatus).or().isNull(PostTag::getStatus))
                .list();
        if (!staleTags.isEmpty()) {
            for (PostTag pt : staleTags) {
                tagService.lambdaUpdate().eq(Tag::getId, pt.getTagId())
                        .setSql("useCount = useCount - 1").update();
            }
            postTagService.remove(new LambdaQueryWrapper<PostTag>()
                    .eq(PostTag::getPostId, postId)
                    .and(w -> w.ne(PostTag::getStatus, sourceStatus).or().isNull(PostTag::getStatus)));
        }
        postTagService.lambdaUpdate()
                .eq(PostTag::getPostId, postId)
                .eq(PostTag::getStatus, sourceStatus)
                .set(PostTag::getStatus, finalStatus)
                .update();
    }

    /**
     * 帖子状态变更后同步 ES：满足索引条件（已发布+审核通过+公开+未删除）发 INDEX，否则发 DELETE。
     * 消息经 {@code es.post.index} 由 rec 服务的 PostIndexConsumer 异步消费，不阻塞主流程。
     *
     * <p>索引条件原在 EsPostSyncService.shouldIndex 单点判断（ES 归 rec 后内联至此，
     * 与原实现逐条对齐：已删除 / 已发布 / 审核通过 / 公开）。</p>
     */
    private void syncPostToEs(Long postId) {
        if (postId == null) {
            return;
        }
        Post p = getById(postId);
        boolean shouldIndex = p != null
                && p.getIsDelete() != null && p.getIsDelete() == 0
                && PostStatusEnum.PUBLISHED.matches(p.getStatus())
                && PostAuditStatusEnum.APPROVED.matches(p.getAuditStatus())
                && PostVisibilityEnum.PUBLIC.matches(p.getVisibility());
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_POST_INDEX,
                new PostIndexMessage(postId, shouldIndex
                        ? PostIndexMessage.ACTION_INDEX
                        : PostIndexMessage.ACTION_DELETE));
    }

    /**
     * 浏览埋点：详情页打开时调用一次（仅登录用户生效）。
     *
     * <p><b>游客不参与</b>：未登录直接忽略，不累加 viewCount、不写浏览历史
     * （接口虽 {@code @SaIgnore} 放行，但这里静默返回，避免游客请求触发 401 弹登录）。
     * 登录用户：viewCount 原子 +1（{@link CountUtils#increment}），
     * 并 upsert 写浏览历史（写失败仅告警，不影响浏览主流程）。</p>
     */
    @Override
    @Transactional
    public void recordView(Long id) {
        // 游客不参与浏览统计
        if (!StpUtil.isLogin()) {
            return;
        }
        Post post = getById(id);
        ThrowUtils.throwIf(post == null || !Objects.equals(post.getStatus(), PostStatusEnum.PUBLISHED.getCode()),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在或未发布");
        // viewCount 原子 +1
        CountUtils.increment(this, Post::getId, id, "viewCount", 1);
        // 浏览历史 upsert（去重累计，重复浏览只刷新 lastViewAt）
        try {
            viewHistoryService.record(StpUtil.getLoginIdAsLong(), id);
        } catch (Exception e) {
            log.warn("浏览历史记录失败 postId={}: {}", id, e.getMessage());
        }

        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_EVT_VIEW,
                new ViewEventMessage(StpUtil.getLoginIdAsLong(), id,post.getTopic(),post.getType(),post.getBoardId(),null));
    }

    @Override
    public void removeViewed(Long postId) {
        ThrowUtils.throwIf(postId == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        viewHistoryService.removeOne(StpUtil.getLoginIdAsLong(), postId);
    }

    @Override
    public void clearViewed() {
        viewHistoryService.clearAll(StpUtil.getLoginIdAsLong());
    }

    /**
     * 我的浏览历史（需登录，本人视角）：按<b>最近一次浏览时间倒序</b>分页返回。
     *
     * <p>流程：分页查 {@code view_history}（orderBy lastViewAt DESC）→ 批量查帖并过滤
     * 已删除/非已发布 → 因 IN 查询结果无序，按 view_history 原顺序重排 → 复用列表装配
     * （作者信息批量查 + {@code fillIsLiked}）。</p>
     *
     * <p><b>total 语义</b>：保持 view_history 总条数（含已删/下架帖占位），
     * 被删/下架帖不展示，实际返回可能略少于 pageSize。</p>
     */
    @Override
    public IPage<PostBrowseVO> listViewedPosts(long current, long pageSize) {
        Long loginId = StpUtil.getLoginIdAsLong();
        // 1. 分页查浏览历史，最近浏览优先
        Page<ViewHistory> vhPage = viewHistoryService.lambdaQuery()
                .eq(ViewHistory::getUserId, loginId)
                .orderByDesc(ViewHistory::getLastViewAt)
                .page(new Page<>(current, pageSize));
        List<Long> postIds = vhPage.getRecords().stream().map(ViewHistory::getPostId).toList();
        if (postIds.isEmpty()) {
            return new Page<>(current, pageSize);
        }
        // 2. 批量查帖 + 过滤已删除/非已发布
        List<Post> posts = listByIds(postIds).stream()
                .filter(p -> Objects.equals(p.getStatus(), PostStatusEnum.PUBLISHED.getCode()))
                .toList();
        // 3. IN 查询无序，按 view_history 原顺序重排
        //    toMap 必须带合并函数：重复 key 时会抛 IllegalStateException（防 IN 查询返回重复行/脏数据）
        Map<Long, Post> byId = posts.stream().collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
        List<Post> ordered = postIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        if (ordered.isEmpty()) {
            IPage<PostBrowseVO> empty = new Page<>(vhPage.getCurrent(), vhPage.getSize(), vhPage.getTotal());
            empty.setRecords(List.of());
            return empty;
        }
        // 4. 复用列表装配：作者信息批量查 + fillIsLiked
        List<Long> authorIds = ordered.stream().map(Post::getUserId).distinct().toList();
        Map<Long, User> userMap = innerUserService.listByIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<PostBrowseVO> voList = ordered.stream()
                .map(p -> buildPostBrowseVO(p, userMap))
                .toList();
        // 批量填充板块对象（无板块帖自动跳过，防 N+1）
        boardBriefFiller.fillBoardBrief(voList);
        // 批量填充话题标签（post_tag + tag 两表各查一次，防 N+1）
        tagBriefFiller.fillTags(voList);
        fillIsLiked(voList);
        fillIsCollected(voList);
        IPage<PostBrowseVO> result = new Page<>(vhPage.getCurrent(), vhPage.getSize(), vhPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    /**
     * 我的收藏列表（需登录，本人视角）：按<b>收藏时间倒序</b>分页返回。
     *
     * <p>流程：分页查 post_collect（orderBy createdAt DESC，folderId=0 默认收藏夹）→
     * 批量查帖并过滤已删除/非已发布 → 按收藏顺序重排 → 复用列表装配
     * （buildPostBrowseVO + fillIsLiked + fillIsCollected）。与 listViewedPosts 同构。</p>
     */
    @Override
    public IPage<PostBrowseVO> listMyCollect(long current, long pageSize) {
        Long loginId = StpUtil.getLoginIdAsLong();
        // 1. 分页查我的收藏关系（默认收藏夹），最近收藏优先 —— 收藏归 interaction，经 Dubbo 分页查询
        List<PostCollect> relations = innerInteractionService.pageMyCollectRelations(loginId, current, pageSize);
        long collectTotal = innerInteractionService.countMyCollect(loginId);
        List<Long> postIds = relations.stream().map(PostCollect::getPostId).toList();
        if (postIds.isEmpty()) {
            return new Page<>(current, pageSize);
        }
        // 2. 批量查帖 + 过滤已删除/非已发布
        List<Post> posts = listByIds(postIds).stream()
                .filter(p -> Objects.equals(p.getStatus(), PostStatusEnum.PUBLISHED.getCode()))
                .toList();
        // 3. IN 查询无序，按收藏顺序重排
        //    toMap 必须带合并函数：重复 key 时会抛 IllegalStateException（防 IN 查询返回重复行/脏数据）
        Map<Long, Post> byId = posts.stream().collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
        List<Post> ordered = postIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        if (ordered.isEmpty()) {
            IPage<PostBrowseVO> empty = new Page<>(current, pageSize, collectTotal);
            empty.setRecords(List.of());
            return empty;
        }
        // 4. 复用列表装配
        List<Long> authorIds = ordered.stream().map(Post::getUserId).distinct().toList();
        Map<Long, User> userMap = innerUserService.listByIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<PostBrowseVO> voList = ordered.stream()
                .map(p -> buildPostBrowseVO(p, userMap))
                .toList();
        // 批量填充板块对象（无板块帖自动跳过，防 N+1）
        boardBriefFiller.fillBoardBrief(voList);
        // 批量填充话题标签（post_tag + tag 两表各查一次，防 N+1）
        tagBriefFiller.fillTags(voList);
        fillIsLiked(voList);
        fillIsCollected(voList);
        IPage<PostBrowseVO> result = new Page<>(current, pageSize, collectTotal);
        result.setRecords(voList);
        return result;
    }

    /**
     * 列表批量装配「是否已收藏」（经 Dubbo 批量查 interaction；未登录跳过）。
     * 对称于 {@link #fillIsLiked(List)}，收藏关系归 interaction 服务。
     */
    private void fillIsCollected(List<PostBrowseVO> voList) {
        if (voList == null || voList.isEmpty() || !StpUtil.isLogin()) {
            return;
        }
        long loginId = StpUtil.getLoginIdAsLong();
        List<Long> postIds = voList.stream()
                .map(PostBrowseVO::getId)
                .filter(Objects::nonNull)
                .toList();
        if (postIds.isEmpty()) {
            return;
        }
        Map<Long, Boolean> collectedMap = innerInteractionService.getPostCollectedMap(loginId, postIds);
        voList.forEach(vo -> {
            if (vo.getId() != null) {
                vo.setIsCollected(collectedMap.getOrDefault(vo.getId(), false));
            }
        });
    }

    /**
     * 站外分享埋点：shareCount 原子 +1 + 写分享流水（channel 渠道，无接收者，不通知）。
     *
     * <p>分享是离散动作（可重复），事务内保证「计数 + 流水」原子性；前端在分享成功回调时调用。</p>
     */
    @Override
    @Transactional
    public void recordShare(Long id, Integer channel) {
        Long loginId = StpUtil.getLoginIdAsLong();
        Post post = getById(id);
        ThrowUtils.throwIf(post == null || !Objects.equals(post.getStatus(), PostStatusEnum.PUBLISHED.getCode()),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在或未发布");
        // shareCount 原子 +1
        CountUtils.increment(this, Post::getId, id, "shareCount", 1);
        // 写分享流水（站外，无接收者）
        PostShare ps = new PostShare();
        ps.setPostId(id);
        ps.setUserId(loginId);
        ps.setChannel(channel == null ? 0 : channel);
        ps.setTargetUserId(null);
        postShareMapper.insert(ps);
    }

    /**
     * 站内分享给指定用户：shareCount 原子 +1 + 写分享流水（targetUserId），
     * 事务提交后发 {@code evt.share} 消息异步通知接收者（type=8，按天幂等）。
     */
    @Override
    @Transactional
    public void recordShareTo(Long id, Long targetUserId) {
        Long loginId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(targetUserId == null, ErrorCode.PARAMS_ERROR, "接收用户不能为空");
        ThrowUtils.throwIf(Objects.equals(loginId, targetUserId), ErrorCode.PARAMS_ERROR, "不能分享给自己");
        User target = innerUserService.getById(targetUserId);
        ThrowUtils.throwIf(target == null, ErrorCode.NOT_FOUND_ERROR, "接收用户不存在");
        Post post = getById(id);
        ThrowUtils.throwIf(post == null || !Objects.equals(post.getStatus(), PostStatusEnum.PUBLISHED.getCode()),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在或未发布");
        // shareCount 原子 +1
        CountUtils.increment(this, Post::getId, id, "shareCount", 1);
        // 写分享流水（站内，有接收者）
        PostShare ps = new PostShare();
        ps.setPostId(id);
        ps.setUserId(loginId);
        ps.setChannel(0);
        ps.setTargetUserId(targetUserId);
        postShareMapper.insert(ps);
        // 事务提交后异步通知接收者（type=8 转发/分享）—— 经 evt.share 由 notify/rec 消费
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_EVT_SHARE,
                new ShareEventMessage(loginId, id, targetUserId));
    }
}
