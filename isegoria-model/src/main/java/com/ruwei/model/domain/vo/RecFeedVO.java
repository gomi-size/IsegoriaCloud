package com.ruwei.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 推荐流返回结果（游标分页）。
 *
 * <p>接口形态为游标分页：{@code nextCursor} = 本页最后一条 postId（字符串，
 * 全局 ToStringSerializer 防雪花精度丢失），首屏不传；{@code hasMore=false}
 * 即无更多（此时 nextCursor 为 null）。曝光去重由服务端 Redis 曝光档案
 * （feed:exposure:{uid}，7 天 TTL）负责：feed 返回时自动回写本页曝光，
 * 取页时剔除已曝光帖顺延补齐；负反馈「不感兴趣」帖同写入该 ZSet，永不放出。</p>
 */
@Data
public class RecFeedVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页帖子卡片列表（已按重排规则排序，已装配作者昵称头像 / isLiked / isCollected）。
     */
    private List<PostBrowseVO> postBrowseVOList;

    /**
     * 下次请求游标 = 本页最后一条 postId（Long 全局序列化为字符串下发，前端 string 接收/回传）；
     * hasMore=false 时为 null。
     */
    private Long nextCursor;

    /**
     * 是否还有更多：本页条数不足 pageSize 即 false（前端据此停止加载）。
     */
    private Boolean hasMore;
}
