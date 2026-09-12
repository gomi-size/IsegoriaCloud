package com.ruwei.post.controller.manager;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.model.dto.AuditlogQueryDTO;
import com.ruwei.model.entity.Auditlog;
import com.ruwei.post.service.AuditlogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核日志管理端接口。
 *
 * <p>提供审核日志的分页查询能力，需 {@code admin} 角色（通过 {@code @SaCheckRole("admin")} 校验，
 * 角色由 {@code user.admin} 字段推导）。</p>
 *
 * @see AuditlogService
 */
@RestController
@RequestMapping("/admin/audilog")
@SaCheckRole("admin")
public class AuditlogController {

    @Resource
    private AuditlogService auditlogService;

    /**
     * 审核日志分页查询（管理端）。
     *
     * <p>条件与用户/帖子列表一致：id / adminId / targetType / targetId / action 精确匹配，
     * remark / createdAt 模糊匹配（createdAt 传 "2026-08-05" 可查当天）；
     * 未传排序字段时默认按创建时间倒序（最新在前）。</p>
     *
     * @param dto 查询条件（current / pageSize / sortField / sortOrder + 筛选字段）
     * @return 审核日志分页结果
     */
    @PostMapping("/list")
    public BaseResponse<IPage<Auditlog>> listAuditLogs(@RequestBody AuditlogQueryDTO dto) {
        return ResultUtils.success(auditlogService.listAuditLogs(dto));
    }
}
