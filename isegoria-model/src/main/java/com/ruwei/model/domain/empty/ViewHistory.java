package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 用户浏览历史实体，对应数据库表 {@code view_history}。
 *
 * <p><b>去重状态表</b>（与 userBehavior 行为流水职责分离）：每 (userId, postId) 仅一行，
 * 首次浏览由 {@code ViewHistoryMapper.upsertView} 插入（viewCount=1），再次浏览命中唯一键
 * {@code ukUserPost} 走 ON DUPLICATE KEY UPDATE 累计次数并刷新最近浏览时间。
 * 「我的浏览历史」列表按 {@code lastViewAt} 倒序（最近一次浏览优先）。</p>
 *
 * <p>游客不写本表（浏览埋点接口对未登录直接忽略）。</p>
 *
 * @TableName view_history
 */
@TableName("view_history")
@Data
public class ViewHistory {

    /**
     * 主键（代码层雪花 ASSIGN_ID，DB 自增仅兜底）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 浏览者内部 id（=loginId）
     */
    private Long userId;

    /**
     * 帖子内部 id
     */
    private Long postId;
    /**
     * 累计浏览次数（每次浏览 +1）
     */
    private Integer viewCount;

    /**
     * 最近一次浏览时间（upsert 时刷新；列表排序依据）
     */
    private Date lastViewAt;

    /**
     * 首次浏览时间，由数据库默认值生成。
     * 列名 {@code createdAt} 与字段名一致；插入/更新策略均设为 NEVER，交由数据库维护。
     */
    @TableField(value = "createdAt", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createdAt;
}
