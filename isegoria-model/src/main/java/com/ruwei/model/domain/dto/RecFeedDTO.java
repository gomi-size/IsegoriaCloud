package com.ruwei.domain.dto;

import com.ruwei.domain.vo.PostBrowseVO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 推荐流请求（游标分页）。
 *
 * <p>接口形态为游标分页：{@code cursor} = 上页最后一条的 postId（字符串，
 * 雪花 id 防前端精度丢失），首屏不传；返回条数 &lt; pageSize 即无更多。</p>
 */
@Data
public class RecFeedDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 上页最后一条 postId（字符串），首屏不传
     */
    private Long cursor;

    /**
     * 每页条数，默认 10，上限 20（Service 内 clamp）
     */
    private Integer pageSize = 10;

    /**
     * recommend 综合（四层漏斗）/ discover 热点+冷启动；默认 recommend
     */
    private String tab = "recommend";

}
