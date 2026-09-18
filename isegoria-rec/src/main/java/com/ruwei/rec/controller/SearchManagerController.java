package com.ruwei.rec.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.rec.service.EsPostSyncService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ES 索引运维接口（仅 admin）——上线首刷 / 索引损坏重建用。
 *
 * <p>日常不需要调用：增量同步由 {@code es.post.index} MQ 消息链路自动完成，
 * 缺失由 {@code EsReconcileTask}（每日 3:00）对账兜底。
 * 本接口只解决"全新环境里存量数据没有事件可触发"的冷启动问题。</p>
 *
 * <p>注意：rec 当前的 {@code StpInterfaceImpl} 若尚未添加（见 phase10 §9.2），
 * {@code @SaCheckRole("admin")} 会因角色列表为空而全部拒绝——两者要么一起做，要么本接口先用 {@code @SaCheckLogin}。</p>
 */
@RestController
@RequestMapping("/admin/search")
@SaCheckRole("admin")
public class SearchManagerController {

    @Resource
    private EsPostSyncService esPostSyncService;

    /**
     * 全量重建 ES 索引（服务端同步执行，数据量大时耗时较长，且会打满 CPU）。
     *
     * @return 固定提示串（进度看日志）
     */
    @PostMapping("/reindex")
    public BaseResponse<String> reindex() {
        esPostSyncService.fullReindex();
        return ResultUtils.success("ES 全量重建完成，详见 rec 日志");
    }
}