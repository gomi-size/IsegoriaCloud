package com.ruwei.post.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mybatis.QueryWrapperUtils;
import com.ruwei.model.dto.AuditlogQueryDTO;
import com.ruwei.model.entity.Auditlog;
import com.ruwei.post.mapper.AuditlogMapper;
import com.ruwei.post.service.AuditlogService;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【auditlog(审核日志表)】的数据库操作Service实现
* @createDate 2026-08-13 16:00:43
*/
@Service
public class AuditlogServiceImpl extends ServiceImpl<AuditlogMapper, Auditlog>
    implements AuditlogService {

    @Override
    public IPage<Auditlog> listAuditLogs(AuditlogQueryDTO dto) {
        ThrowUtils.throwIf(BeanUtil.isEmpty(dto), ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        QueryWrapper<Auditlog> queryWrapper = QueryWrapperUtils.getAuditlogQueryWrapper(dto);
        return this.page(new Page<>(dto.getCurrent(), dto.getPageSize()), queryWrapper);
    }

}




