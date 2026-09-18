package com.ruwei.rec.service;


import com.ruwei.model.dto.RecExposureDTO;
import com.ruwei.model.dto.RecFeedDTO;
import com.ruwei.model.dto.RecFeedbackDTO;
import com.ruwei.model.vo.RecFeedVO;

/**
 * 推荐流服务（Phase 1 规则驱动四层漏斗：召回 → 粗排 → 精排 → 重排）。
 *
 * <p>接口形态为<b>游标分页</b>：cursor = 上页最后一条的 postId（字符串，全局 ToStringSerializer
 * 防雪花精度丢失），首屏不传；返回 {@link RecFeedVO}（本页列表 + nextCursor + hasMore），
 * 返回条数 &lt; pageSize 即无更多。</p>
 */
public interface RecService {

    /**
     * 推荐流（游标分页）。
     *
     * <p>登录用户：tab=recommend 走四层漏斗个性化；tab=discover 只走热点+冷启动。
     * 游客：强制降级 discover 口径（不读关注/板块/标签画像，不写曝光）。</p>
     *
     * @param req 游标分页请求（cursor / pageSize / tab）
     * @return 当前页结果（帖子卡片 + nextCursor + hasMore，已按重排规则排序，返回前已回写曝光）
     */
    RecFeedVO feed(RecFeedDTO req);

    /**
     * 兜底曝光回写：feed 返回时已自动写曝光 ZSet；前端滚动/断网重试时可再调，防丢（幂等）。
     *
     * @param req 帖子内部 id 列表（字符串）
     */
    void recordExposure(RecExposureDTO req);

    /**
     * 负反馈（不感兴趣等）：写 userBehavior(action=8) + 该帖全部兴趣维度短期降权（INCR 负值）。
     *
     * @param req 帖子内部 id（字符串） + 负反馈类型
     */
    void feedback(RecFeedbackDTO req);

}
