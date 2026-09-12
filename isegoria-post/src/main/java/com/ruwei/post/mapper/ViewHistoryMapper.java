package com.ruwei.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruwei.model.entity.ViewHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * @description 针对表【view_history(用户浏览历史表)】的数据库操作Mapper
 * @Entity com.ruwei.model.entity.ViewHistory
 */
public interface ViewHistoryMapper extends BaseMapper<ViewHistory> {

    /**
     * 浏览历史 upsert：命中唯一键 {@code ukUserPost(userId, postId)} 即累计，无并发竞态。
     *
     * <p>首次浏览 → 插入新行（viewCount=1）；再次浏览 → 不新增行，只执行 UPDATE 分支：
     * {@code viewCount = viewCount + 1} 且 {@code lastViewAt = NOW()}（最近一次浏览优先）。
     * 一条 SQL 在数据库层原子完成"查重 + 计数 + 刷新时间"。</p>
     *
     * @param id     主键（雪花 id，由调用方 {@code IdWorker.getId()} 生成，对齐项目 ASSIGN_ID 约定）
     * @param userId 浏览者内部 id（=loginId）
     * @param postId 帖子内部 id
     * @return 影响行数（1=插入，2=更新；业务无需区分）
     */
    @Insert("INSERT INTO view_history (id, userId, postId, viewCount, lastViewAt) " +
            "VALUES (#{id}, #{userId}, #{postId}, 1, NOW()) " +
            "ON DUPLICATE KEY UPDATE viewCount = viewCount + 1, lastViewAt = NOW()")
    int upsertView(@Param("id") Long id, @Param("userId") Long userId, @Param("postId") Long postId);
}
