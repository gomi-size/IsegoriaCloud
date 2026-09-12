package com.ruwei.post.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.model.dto.TagDTO;
import com.ruwei.model.entity.Tag;
import com.ruwei.model.vo.TagVO;
import com.ruwei.post.service.TagService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 标签（话题）相关接口。
 *
 * <p>查询接口公开（无需登录）；增删改属平台管理行为，标注 {@code @SaCheckRole("admin")}，
 * 如需放开为登录用户即可去掉该注解。</p>
 */
@RestController
@RequestMapping("/tag")
@SaCheckLogin
public class TagController {

    @Resource
    private TagService tagService;

    /**
     * 热门标签榜（按使用次数倒序，取前 20）
     */
    @GetMapping("/hot")
    @SaIgnore
    public BaseResponse<List<Tag>> getHotTags() {
        return ResultUtils.success(tagService.getHotTags(20));
    }

    /**
     * 标签列表（仅正常标签，按使用次数倒序）
     */
    @GetMapping("/list")
    public BaseResponse<List<TagVO>> listTags() {
        return ResultUtils.success(tagService.listTags());
    }

    /**
     * 标签全量列表（含禁用，按使用次数倒序）—— 管理后台标签管理专用
     */
    @SaCheckRole("admin")
    @GetMapping("/adminList")
    public BaseResponse<List<TagVO>> listTagsAll() {
        return ResultUtils.success(tagService.listTagsAll());
    }

    /**
     * 标签详情（按 id）
     */
    @GetMapping("/{id}")
    public BaseResponse<TagVO> getTag(@PathVariable Long id) {
        return ResultUtils.success(tagService.getTag(id));
    }

    /**
     * 新增标签（name 唯一）
     */
    @PostMapping("/add")
    public BaseResponse<TagVO> addTag(@RequestBody TagDTO tagDTO) {
        return ResultUtils.success(tagService.addTag(tagDTO));
    }

    /**
     * 更新标签名（排除自身后校验唯一）
     */
    @SaCheckRole("admin")
    @PostMapping("/update")
    public BaseResponse<String> updateTag(@RequestParam Long id, @RequestBody TagDTO tagDTO) {
        tagService.updateTag(id, tagDTO);
        return ResultUtils.success("更新成功");
    }

    /**
     * 删除标签（物理删除，并清理 post_tag 关联）
     */
    @SaCheckRole("admin")
    @DeleteMapping("/{id}")
    public BaseResponse<String> deleteTag(@PathVariable Long id) {
        tagService.deleteTag(id);
        return ResultUtils.success("删除成功");
    }
}
