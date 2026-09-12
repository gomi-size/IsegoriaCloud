package com.ruwei.post.schedule;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruwei.model.enums.PostAuditStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;
import com.ruwei.model.entity.Post;
import com.ruwei.post.manager.ScoreConfigManager;
import com.ruwei.post.service.PostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Component
//热度对账，每五分钟执行一次
public class ScoreRecalcJob {

    @Resource
    private PostService postService;

    @Resource
    private ScoreConfigManager scoreConfigManager;

    @Scheduled(cron = "0 */5 * * * *")
    public void recalc(){
        //1.分页去全量【已发布】，并且是已公开
        long current=1;
        long pageSize=500;

        while(true){
            Page<Post> page = postService.lambdaQuery().eq(Post::getAuditStatus, PostAuditStatusEnum.APPROVED.getCode())
                    .eq(Post::getVisibility, PostVisibilityEnum.PUBLIC.getCode())
                    .orderByAsc(Post::getId)
                    .page(new Page<>(current++, pageSize));
            List<Post> pageRecords = page.getRecords();
            if(pageRecords.isEmpty()){
                break;
            }
            //需要进行逐条计算，并更新（收集变化量》0.001的才更新）
            List<Post> toUpdate = pageRecords.stream()
                    //过滤没有创建的时间的（一定会有创建时间）
                    .filter(p -> p.getCreatedAt() != null)
                    .map(p -> {
                        p.setScore(calsScore(p));
                        return p;
                    })
                    //进行剔除为零的还有变化量十分小的
                    .filter(p -> p.getScore() != null && p.getScore().compareTo(BigDecimal.ZERO) > 0)
                    .toList();
            if(!toUpdate.isEmpty()) postService.updateBatchById(toUpdate);
            //如果数量不足那就直接退出后续肯定也不足够
            if (page.getRecords().size() < pageSize) break;
        }
    }

    private BigDecimal calsScore(Post p){
        //1.计算帖子的发布时长
        long dtHours = (System.currentTimeMillis() - p.getCreatedAt().getTime()) / 3600_000L;
        //2.权重参数从 score_config 动态读取（ScoreConfigManager 内存缓存，管理端可调）
        double base = scoreConfigManager.getLikeW() * Math.log10((p.getLikeCount()) + 1.0)
                + scoreConfigManager.getCommentW() * Math.log10((p.getCommentCount()) + 1.0)
                + scoreConfigManager.getCollectW() * Math.log10((p.getCollectCount()) + 1.0)
                + scoreConfigManager.getShareW() * Math.log10((p.getShareCount()) + 1.0)
                //拉踩/举报为负向降权项（默认 -1 / -5）；null 兜底 0 防旧库未补列
                + scoreConfigManager.getDislikeW() * Math.log10((p.getDislikeCount() == null ? 0 : p.getDislikeCount()) + 1.0)
                + scoreConfigManager.getReportW() * Math.log10((p.getReportCount() == null ? 0 : p.getReportCount()) + 1.0);

        //3.时间衰减
        double score = base * Math.exp(-dtHours / scoreConfigManager.getTauHours());
        //4.转化为BigDecimal存储
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }
}
