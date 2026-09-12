package com.ruwei.post.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.entity.ViewHistory;

/**
 * 用户浏览历史服务（去重状态表，upsert 累计）。
 *
 * <p>与 {@code userBehavior} 行为流水职责分离：本服务只维护「浏览历史」这一业务状态
 * （每用户每帖一行，重复浏览累计次数 + 刷新最近浏览时间），供在线查询
 * 「我的浏览历史 / 看过没看过」；行为流水由推荐模块的事件监听器另行写入。</p>
 */
public interface ViewHistoryService extends IService<ViewHistory> {

    /**
     * 记录一次浏览：upsert 累计（首次插入 viewCount=1，再次浏览 +1 并刷新 lastViewAt）。
     *
     * <p>调用方需保证传入登录用户内部 id（游客不调用本方法）；
     * 单条 SQL 原子完成，无并发竞态。</p>
     *
     * @param userId 浏览者内部 id（=loginId）
     * @param postId 帖子内部 id
     */
    void record(Long userId, Long postId);

    /**
     * 删除单条浏览记录（仅能删自己的）。
     *
     * <p>必须显式携带 {@code userId} 条件（MyBatis-Plus 的 remove 无 wrapper 会删全表，
     * 此处按唯一键 ukUserPost(userId, postId) 精确命中一行，物理删除，无逻辑删除列）。</p>
     *
     * @param userId 浏览者内部 id（=loginId）
     * @param postId 帖子内部 id
     * @return 受影响行数（0=该记录不存在）
     */
    int removeOne(Long userId, Long postId);

    /**
     * 清空本人全部浏览历史。
     *
     * <p>同样必须带 {@code userId} 条件，只删当前用户的行，杜绝误删全表。</p>
     *
     * @param userId 浏览者内部 id（=loginId）
     * @return 删除行数
     */
    int clearAll(Long userId);
}
