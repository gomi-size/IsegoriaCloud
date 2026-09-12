package com.ruwei.post.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.model.dto.TagDTO;
import com.ruwei.model.entity.PostTag;
import com.ruwei.model.entity.Tag;
import com.ruwei.model.vo.TagVO;
import com.ruwei.post.mapper.TagMapper;
import com.ruwei.post.service.PostTagService;
import com.ruwei.post.service.TagService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
* @author Administrator
* @description 针对表【tag(标签表)】的数据库操作Service实现
* @createDate 2026-08-05 10:24:02
*/
@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag>
    implements TagService {



    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    @Resource
    private PostTagService postTagService;

    /**
     * 热门标签榜（仅 status=1 正常标签，按 useCount 倒序，LIMIT 限制条数）。
     */
    @Override
    public List<Tag> getHotTags(int limit) {
        return lambdaQuery()
                .eq(Tag::getStatus, 1)
                .orderByDesc(Tag::getUseCount)
                .last("LIMIT " + limit)
                .list();
    }

    /**
     * 新增标签（name 唯一，useCount=0、status=1）。
     */
    @Override
    public TagVO addTag(TagDTO dto) {
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto) || StrUtil.isBlank(dto.getName()),
                ErrorCode.PARAMS_ERROR, "标签名不能为空");
        String name = dto.getName().trim();
        ThrowUtils.throwIf(name.length() > 64, ErrorCode.PARAMS_ERROR, "标签名最多64字");

        sensitiveWordFilter.checkStrict(dto.getName(),"标签");

        // name 唯一（ukName 兜底，先查做友好提示）
        Long exists = lambdaQuery().eq(Tag::getName, name).count();
        ThrowUtils.throwIf(exists != null && exists > 0, ErrorCode.OPERATION_ERROR, "标签已存在");
        sensitiveWordFilter.checkStrict(dto.getName(), "话题");
        Tag tag = new Tag();
        tag.setName(name);
        tag.setUseCount(0);
        tag.setStatus(1);
        boolean saved = save(tag);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "新增失败");
        return BeanUtil.copyProperties(tag, TagVO.class);
    }

    /**
     * 更新标签名（排除自身后校验 name 唯一）。
     */
    @Override
    public void updateTag(Long id, TagDTO dto) {
        ThrowUtils.throwIf(id == null || BeanUtil.isEmpty(dto) || StrUtil.isBlank(dto.getName()),
                ErrorCode.PARAMS_ERROR, "参数不能为空");
        Tag tag = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(tag), ErrorCode.NOT_FOUND_ERROR, "标签不存在");

        String name = dto.getName().trim();
        ThrowUtils.throwIf(name.length() > 64, ErrorCode.PARAMS_ERROR, "标签名最多64字");
        // 排除自身：其余同名标签不允许
        Long exists = lambdaQuery().eq(Tag::getName, name).ne(Tag::getId, id).count();
        ThrowUtils.throwIf(exists != null && exists > 0, ErrorCode.OPERATION_ERROR, "标签名已被占用");

        boolean updated = lambdaUpdate().eq(Tag::getId, id).set(Tag::getName, name).update();
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新失败");
    }

    /**
     * 删除标签（物理删除，并清理 post_tag 关联，避免孤儿引用）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "标签 id 不能为空");
        Tag tag = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(tag), ErrorCode.NOT_FOUND_ERROR, "标签不存在");

        // 清理关联（post_tag 引用该标签的记录一并删除）
        postTagService.remove(new LambdaQueryWrapper<PostTag>().eq(PostTag::getTagId, id));

        boolean removed = removeById(id);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "删除失败");
    }

    /**
     * 按 id 查询标签详情。
     */
    @Override
    public TagVO getTag(Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "标签 id 不能为空");
        Tag tag = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(tag), ErrorCode.NOT_FOUND_ERROR, "标签不存在");
        return BeanUtil.copyProperties(tag, TagVO.class);
    }

    /**
     * 标签列表（仅 status=1 正常标签，按使用次数倒序）。
     */
    @Override
    public List<TagVO> listTags() {
        return lambdaQuery()
                .eq(Tag::getStatus, 1)
                .orderByDesc(Tag::getUseCount)
                .list().stream()
                .map(t -> BeanUtil.copyProperties(t, TagVO.class))
                .toList();
    }

    /**
     * 标签全量列表（含 status=2 禁用，按使用次数倒序）。
     * 管理后台标签管理专用，返回 useCount / status 供列表与统计展示。
     */
    @Override
    public List<TagVO> listTagsAll() {
        return lambdaQuery()
                .orderByDesc(Tag::getUseCount)
                .list().stream()
                .map(t -> BeanUtil.copyProperties(t, TagVO.class))
                .toList();
    }

    /**
     * 标签查询（用户端）：按名称模糊搜索，仅返回 status=1 正常标签，按使用次数倒序；
     * keyword 为空时返回全部正常标签。
     */
    @Override
    public List<TagVO> searchTags(String keyword) {
        return lambdaQuery()
                .eq(Tag::getStatus, 1)
                .like(StrUtil.isNotBlank(keyword), Tag::getName, keyword)
                .orderByDesc(Tag::getUseCount)
                .list().stream()
                .map(t -> BeanUtil.copyProperties(t, TagVO.class))
                .toList();
    }

    /**
     * 更改标签状态（启用/禁用）：1正常 2禁用，仅这两个值合法；幂等（目标状态相同直接返回）。
     */
    @Override
    public void updateTagStatus(Long id, Integer status) {
        ThrowUtils.throwIf(id == null || status == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        ThrowUtils.throwIf(status != 1 && status != 2, ErrorCode.PARAMS_ERROR, "非法状态，仅支持 1正常 / 2禁用");

        Tag tag = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(tag), ErrorCode.NOT_FOUND_ERROR, "标签不存在");

        if (status.equals(tag.getStatus())) {
            return;
        }
        boolean updated = lambdaUpdate().eq(Tag::getId, id).set(Tag::getStatus, status).update();
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新失败");
    }
}
