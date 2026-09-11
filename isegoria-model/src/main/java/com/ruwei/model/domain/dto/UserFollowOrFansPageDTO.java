package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 用户关注/粉丝列表分页查询条件（入参 DTO）。
 * 分页参数继承自 {@link PageRequest}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserFollowOrFansPageDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 内部主键 id（预留，当前未参与过滤）
     */
    private Long id;

}
