package com.ruwei.post.service;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.dto.AuditlogQueryDTO;
import com.ruwei.model.entity.Auditlog;

/**
* @author Administrator
* @description 针对表【auditlog(审核日志表)】的数据库操作Service
* @createDate 2026-08-13 16:00:43
*/
public interface AuditlogService extends IService<Auditlog> {

    /**
     * 审核日志分页查询（管理端）。
     *
     * <p>条件见 {@link AuditlogQueryDTO}；未传排序字段时默认按创建时间倒序（最新在前）。</p>
     *
     * @param dto 查询条件（current / pageSize / sortField / sortOrder + 筛选字段）
     * @return 审核日志分页结果
     */
    IPage<Auditlog> listAuditLogs(AuditlogQueryDTO dto);

}
