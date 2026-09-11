package com.ruwei.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 兜底曝光回写请求（前端滚动上报，防 feed 自动回写丢失）。
 */
@Data
public class RecExposureDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 已曝光的帖子内部 id 列表
     */
    private List<Long> postIds;
}
