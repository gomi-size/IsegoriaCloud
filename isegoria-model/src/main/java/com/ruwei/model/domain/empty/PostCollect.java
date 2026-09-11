package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 帖子收藏实体，对应数据库表 {@code post_collect}。
 *
 * <p><b>物理删 toggle</b>（对齐 {@code post_like}）：收藏 = insert、取消收藏 = delete，
 * 无软取消状态机。唯一键 {@code ukUserPostFolder(userId, postId, folderId)} 防重复收藏。</p>
 *
 * <p><b>收藏夹预留</b>：{@link #folderId} 为预留列，Phase 1 代码写死 0（默认收藏夹）；
 * 用 0 而非 NULL 是保证唯一键在未分组阶段仍能防重。未来做收藏夹时 folder 表 id 从 1 自增，
 * 0 留给默认夹，同一帖子收进多个收藏夹由唯一键天然支持。</p>
 *
 * @TableName post_collect
 */
@TableName("post_collect")
@Data
public class PostCollect {

    /**
     * 主键（代码层雪花 ASSIGN_ID，DB 自增仅兜底）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 帖子内部 id
     */
    private Long postId;

    /**
     * 收藏者内部 id（=loginId）
     */
    private Long userId;

    /**
     * 收藏夹 id：0=默认收藏夹（Phase 1 未分组，预留列）
     */
    private Long folderId;

    /**
     * 收藏时间，由数据库默认值生成。
     * 列名 {@code createdAt} 与字段名一致；插入/更新策略均设为 NEVER，交由数据库维护。
     */
    @TableField(value = "createdAt", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createdAt;
}
