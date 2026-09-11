package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 通知分页查询条件（入参 DTO）。
 * 分页参数继承自 {@link PageRequest}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotificationQueryDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 1点赞 2评论 3回复 4关注 5@提及 6系统 7收藏 8转发
     */
    private Integer type;
}
