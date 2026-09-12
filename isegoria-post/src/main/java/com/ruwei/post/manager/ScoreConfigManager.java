package com.ruwei.post.manager;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.model.dto.ScoreConfigDTO;
import com.ruwei.model.entity.ScoreConfig;
import com.ruwei.post.mapper.ScoreConfigMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 热度评分参数配置管理器（score_config 单行配置的内存缓存）。
 *
 * <p>对标 {@code SensitiveWordFilter} 的「启动加载 + 写后热刷新」模式：
 * Job 每 5 分钟全量重算热度分、内部多轮分页，直接读内存 {@code volatile} 字段零 IO；
 * 管理端改配置后调用 {@link #refresh()} 原子替换缓存，下次 Job 运行即生效，无需重启。</p>
 *
 * <p>兜底策略：SQL 已预置初始行，若表为空或加载失败，回落默认常量
 * （1.0 / 2.0 / 3.0 / 4.0 / -1.0 / -5.0 / 48.0），避免权重全 0 导致热度分全量归零。</p>
 */
@Slf4j
@Component
public class ScoreConfigManager {

    /** 默认点赞权重 */
    private static final double DEFAULT_LIKE_W = 1.0;
    /** 默认评论权重 */
    private static final double DEFAULT_COMMENT_W = 2.0;
    /** 默认收藏权重 */
    private static final double DEFAULT_COLLECT_W = 3.0;
    /** 默认分享权重 */
    private static final double DEFAULT_SHARE_W = 4.0;
    /** 默认拉踩(踩)权重（负向降权） */
    private static final double DEFAULT_DISLIKE_W = -1.0;
    /** 默认举报权重（负向重扣） */
    private static final double DEFAULT_REPORT_W = -5.0;
    /** 默认时间衰减半衰期(小时) */
    private static final double DEFAULT_TAU_HOURS = 48.0;

    @Resource
    private ScoreConfigMapper scoreConfigMapper;

    /** 当前生效配置缓存（refresh 时原子替换，读线程无锁） */
    private volatile ScoreConfig cache;

    /**
     * 容器启动后加载一次配置。
     */
    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 从 score_config 表加载最新配置并原子替换缓存。
     *
     * <p>单行配置表按 id 升序取第一行（业务上只维护一行）；
     * 表空或查询异常时回落默认常量，保证 Job 永远有合法参数可算。</p>
     */
    public void refresh() {
        ScoreConfig loaded;
        try {
            loaded = scoreConfigMapper.selectOne(
                    new LambdaQueryWrapper<ScoreConfig>().orderByAsc(ScoreConfig::getId).last("limit 1"));
        } catch (Exception e) {
            log.warn("score_config 加载失败，使用默认参数：{}", e.getMessage());
            this.cache = defaultConfig();
            return;
        }
        this.cache = loaded == null ? defaultConfig() : loaded;
        log.info("热度评分参数加载完成：likeW={}, commentW={}, collectW={}, shareW={}, dislikeW={}, reportW={}, tauHours={}",
                getLikeW(), getCommentW(), getCollectW(), getShareW(), getDislikeW(), getReportW(), getTauHours());
    }

    /**
     * 查询当前生效配置（供管理端展示）。
     *
     * @return 配置 DTO
     */
    public ScoreConfigDTO getConfig() {
        ScoreConfig current = cache == null ? defaultConfig() : cache;
        return BeanUtil.copyProperties(current, ScoreConfigDTO.class);
    }

    /**
     * 整体保存配置：落库（首行存在则更新，否则插入）+ 热刷新。
     *
     * <p>校验规则：六个权重仅要求非 null（允许 0 = 该项不参与，允许负数 = 降权项）；
     * {@code tauHours} 必须 &gt; 0，否则热度公式除零。</p>
     *
     * @param dto 配置入参
     * @return 保存成功
     */
    public boolean saveConfig(ScoreConfigDTO dto) {
        ThrowUtils.throwIf(dto == null, ErrorCode.PARAMS_ERROR, "配置参数不能为空");
        ThrowUtils.throwIf(dto.getLikeW() == null || dto.getCommentW() == null || dto.getCollectW() == null
                        || dto.getShareW() == null || dto.getDislikeW() == null || dto.getReportW() == null,
                ErrorCode.PARAMS_ERROR, "六个权重参数均不能为空");
        ThrowUtils.throwIf(dto.getTauHours() == null || dto.getTauHours() <= 0,
                ErrorCode.PARAMS_ERROR, "tauHours 必须大于 0");

        ScoreConfig config = BeanUtil.copyProperties(dto, ScoreConfig.class);
        ScoreConfig existing = scoreConfigMapper.selectOne(
                new LambdaQueryWrapper<ScoreConfig>().orderByAsc(ScoreConfig::getId).last("limit 1"));
        if (existing == null) {
            scoreConfigMapper.insert(config);
        } else {
            config.setId(existing.getId());
            scoreConfigMapper.updateById(config);
        }
        refresh();
        return true;
    }

    public double getLikeW() {
        return cache != null && cache.getLikeW() != null ? cache.getLikeW() : DEFAULT_LIKE_W;
    }

    public double getCommentW() {
        return cache != null && cache.getCommentW() != null ? cache.getCommentW() : DEFAULT_COMMENT_W;
    }

    public double getCollectW() {
        return cache != null && cache.getCollectW() != null ? cache.getCollectW() : DEFAULT_COLLECT_W;
    }

    public double getShareW() {
        return cache != null && cache.getShareW() != null ? cache.getShareW() : DEFAULT_SHARE_W;
    }

    public double getDislikeW() {
        return cache != null && cache.getDislikeW() != null ? cache.getDislikeW() : DEFAULT_DISLIKE_W;
    }

    public double getReportW() {
        return cache != null && cache.getReportW() != null ? cache.getReportW() : DEFAULT_REPORT_W;
    }

    public double getTauHours() {
        return cache != null && cache.getTauHours() != null ? cache.getTauHours() : DEFAULT_TAU_HOURS;
    }

    /**
     * 构造默认兜底配置（与 SQL 初始行一致）。
     */
    private ScoreConfig defaultConfig() {
        ScoreConfig c = new ScoreConfig();
        c.setLikeW(DEFAULT_LIKE_W);
        c.setCommentW(DEFAULT_COMMENT_W);
        c.setCollectW(DEFAULT_COLLECT_W);
        c.setShareW(DEFAULT_SHARE_W);
        c.setDislikeW(DEFAULT_DISLIKE_W);
        c.setReportW(DEFAULT_REPORT_W);
        c.setTauHours(DEFAULT_TAU_HOURS);
        return c;
    }
}
