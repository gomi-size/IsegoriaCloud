package com.ruwei.post.controller.manager;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.model.dto.ScoreConfigDTO;
import com.ruwei.post.manager.ScoreConfigManager;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 热度评分参数管理端接口（score_config 单行配置）。
 *
 * <p>提供当前配置查询与整体更新能力，需 {@code admin} 角色（{@code @SaCheckRole("admin")}）。
 * 更新落库后触发 {@link ScoreConfigManager#refresh()} 内存热刷新，ScoreRecalcJob
 * 下次执行即用新参数，无需改 yaml / 重启。</p>
 *
 * <p>入参语义：likeW/commentW/collectW/shareW 正向加分，dislikeW（拉踩）/reportW（举报）
 * 负向降权（传负数），tauHours 为时间衰减半衰期（必须 &gt; 0）。</p>
 *
 * @see ScoreConfigManager
 */
@RestController
@RequestMapping("/admin/score-config")
@SaCheckRole("admin")
public class ScoreConfigController {

    @Resource
    private ScoreConfigManager scoreConfigManager;

    /**
     * 查询当前生效的热度评分参数。
     *
     * @return 当前配置 DTO
     */
    @GetMapping
    public BaseResponse<ScoreConfigDTO> getConfig() {
        return ResultUtils.success(scoreConfigManager.getConfig());
    }

    /**
     * 整体更新热度评分参数（六权重 + tauHours 一次提交）。
     *
     * @param dto 配置入参（六权重非空，tauHours 必须大于 0）
     * @return 更新结果提示
     */
    @PutMapping
    public BaseResponse<String> updateConfig(@RequestBody ScoreConfigDTO dto) {
        scoreConfigManager.saveConfig(dto);
        return ResultUtils.success("更新成功");
    }
}
