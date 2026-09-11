package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 帖子分享流水实体，对应数据库表 {@code post_share}。
 *
 * <p><b>分享流水（离散动作，可重复）</b>：每次分享都记一条，无唯一键、无取消动作，
 * 区别于收藏那种"一人一帖一条"的关系表。{@link #channel} 与 {@link #targetUserId} 二选一：
 * 站外分享（微信/朋友圈/复制链接等）只记 channel、targetUserId 为空、不通知；
 * 站内分享给指定用户记 targetUserId，触发 {@code ShareEvent} 通知接收者（type=8）。</p>
 *
 * @TableName post_share
 */
@TableName("post_share")
@Data
public class PostShare {

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
     * 分享者内部 id（=loginId）
     */
    private Long userId;

    /**
     * 站外分享渠道：0未知 1微信 2朋友圈 3QQ 4微博 5复制链接（站内分享记 0）
     */
    private Integer channel;

    /**
     * 站内分享接收者内部 id（站外分享为 NULL）
     */
    private Long targetUserId;

    /**
     * 分享时间，由数据库默认值生成。
     * 列名 {@code createdAt} 与字段名一致；插入/更新策略均设为 NEVER，交由数据库维护。
     */
    @TableField(value = "createdAt", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createdAt;
}
