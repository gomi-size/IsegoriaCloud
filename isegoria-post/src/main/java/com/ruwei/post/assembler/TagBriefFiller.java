package com.ruwei.post.assembler;

import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.entity.PostTag;
import com.ruwei.model.entity.Tag;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.post.service.PostTagService;
import com.ruwei.post.service.TagService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 帖子卡片 VO 的<b>话题标签</b>批量装配组件（与 {@link BoardBriefFiller} 同构）。
 *
 * <p><b>职责</b>：为 {@link PostBrowseVO} 列表原地填充
 * {@link PostBrowseVO.TagBrief} 列表（{@code tag.id} / {@code tag.name}，前端拼
 * {@code /tag/{id}} 跳转话题页）。标签关联存在于 post_tag 中间表，一次收集本页 postIds
 * 后批量查询，<b>避免逐帖查询造成 N+1</b>。</p>
 *
 * <p><b>数据来源与过滤</b>：
 * <ul>
 *   <li>只取 {@code post_tag.status = 已发布(1)} 的关联 —— 与列表可见的帖子版本一致，
 *       草稿 / 审核中版本的标签不出现在公开卡片上；</li>
 *   <li>只取 {@code tag.status = 1（正常）} 的标签 —— 被运营禁用的标签不对外展示；</li>
 *   <li>同帖重复关联（脏数据或唯一键缺失）按 (postId, tagId) 去重，顺序保持查询顺序。</li>
 * </ul>
 * </p>
 *
 * <p><b>注意</b>：post 表另有一个 {@code topic} 字段（逗号分隔的 tag id 串，详情
 * {@code PostVO.topic} 用它解析），本组件<b>以 post_tag 关联表为准</b>（它是写入时的权威数据，
 * 带版本状态）；若某批历史帖只有 topic 串而无 post_tag 记录，则其 {@code tags} 为空列表，
 * 属数据补齐问题，不在列表装配层兜底。</p>
 *
 * <p><b>边界</b>：入参为空 / 无 postId 直接返回（零 DB 开销）；装配后每个 VO 的
 * {@code tags} <b>必不为 null</b>（无标签为空列表），前端可直接 v-for。</p>
 *
 * @author ruwei
 */
@Component
public class TagBriefFiller {

    /** tag.status：正常（展示用，禁用的标签不出现在卡片上） */
    private static final int TAG_STATUS_NORMAL = 1;

    @Resource
    private PostTagService postTagService;

    @Resource
    private TagService tagService;

    /**
     * 批量填充话题标签（原地修改入参列表，无返回值）。
     *
     * <p>实现：① 收集本页去重非空 {@code postId}；② {@code post_tag} 按 postIds 批量查
     * （仅已发布版本）；③ 建 postId → 有序 tagId 集合索引；④ tagIds 批量查 tag 表建索引；
     * ⑤ 原地写入 {@code tags}（按关联顺序装配 TagBrief）。</p>
     *
     * @param voList 已组装的 PostBrowseVO 列表（可空；原地填充，不新建）
     */
    public void fillTags(List<PostBrowseVO> voList) {
        if (voList == null || voList.isEmpty()) {
            return;
        }
        // ① 收集本页去重的非空帖子 id
        List<Long> postIds = voList.stream()
                .map(PostBrowseVO::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (postIds.isEmpty()) {
            return;
        }
        // ② 批量查关联表（仅已发布版本，草稿/审核中的标签不外露）
        List<PostTag> relations = postTagService.lambdaQuery()
                .in(PostTag::getPostId, postIds)
                .eq(PostTag::getStatus, PostStatusEnum.PUBLISHED.getCode())
                .list();
        // ③ postId → 有序 tagId 集合（LinkedHashSet 去重且保序）
        Map<Long, Set<Long>> postTagIds = new HashMap<>();
        if (relations != null) {
            for (PostTag relation : relations) {
                if (relation.getPostId() == null || relation.getTagId() == null) {
                    continue;
                }
                postTagIds.computeIfAbsent(relation.getPostId(), k -> new LinkedHashSet<>())
                        .add(relation.getTagId());
            }
        }
        if (postTagIds.isEmpty()) {
            // 本页全部无标签：统一置空列表，避免前端判 null
            voList.forEach(vo -> vo.setTags(List.of()));
            return;
        }
        // ④ 批量查 tag 表（仅正常状态），按 id 建索引
        List<Long> tagIds = postTagIds.values().stream()
                .flatMap(Set::stream)
                .distinct()
                .toList();
        Map<Long, Tag> tagMap = tagService.lambdaQuery()
                .in(Tag::getId, tagIds)
                .eq(Tag::getStatus, TAG_STATUS_NORMAL)
                .list()
                .stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity(), (a, b) -> a));
        // ⑤ 原地填充（按关联顺序装配，查不到的标签跳过）
        voList.forEach(vo -> {
            Set<Long> ids = postTagIds.get(vo.getId());
            if (ids == null || ids.isEmpty()) {
                vo.setTags(List.of());
                return;
            }
            List<PostBrowseVO.TagBrief> tags = ids.stream()
                    .map(tagMap::get)
                    .filter(Objects::nonNull)
                    .map(PostBrowseVO.TagBrief::of)
                    .toList();
            vo.setTags(tags);
        });
    }
}
