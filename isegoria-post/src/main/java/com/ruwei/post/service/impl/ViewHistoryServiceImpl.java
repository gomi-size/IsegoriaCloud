package com.ruwei.post.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.model.entity.ViewHistory;
import com.ruwei.post.mapper.ViewHistoryMapper;
import com.ruwei.post.service.ViewHistoryService;
import org.springframework.stereotype.Service;

/**
 * 用户浏览历史服务实现。
 *
 * <p>浏览历史是<b>去重状态表</b>：首次浏览插入、再次浏览累计，靠
 * {@link ViewHistoryMapper#upsertView} 一条 SQL 原子完成，无并发竞态
 * （对齐项目「计数走 DB 层原子」的约定，但这里行可能不存在，故不用 CountUtils 两步式）。</p>
 */
@Service
public class ViewHistoryServiceImpl extends ServiceImpl<ViewHistoryMapper, ViewHistory>
        implements ViewHistoryService {

    @Override
    public void record(Long userId, Long postId) {
        ThrowUtils.throwIf(userId == null || postId == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        baseMapper.upsertView(IdWorker.getId(), userId, postId);
    }

    @Override
    public int removeOne(Long userId, Long postId) {
        ThrowUtils.throwIf(userId == null || postId == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        // 物理删（本表无 isDelete 逻辑删除列）；带 userId 条件防越权删他人记录
        return baseMapper.delete(new LambdaQueryWrapper<ViewHistory>()
                .eq(ViewHistory::getUserId, userId)
                .eq(ViewHistory::getPostId, postId));
    }

    @Override
    public int clearAll(Long userId) {
        ThrowUtils.throwIf(userId == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        // 高危操作：必须带 userId 条件，只删当前用户的行，杜绝 remove() 无 wrapper 删全表
        return baseMapper.delete(new LambdaQueryWrapper<ViewHistory>()
                .eq(ViewHistory::getUserId, userId));
    }
}
