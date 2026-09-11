package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 板块关注分页查询条件（入参 DTO）。
 *
 * <p>用于「我关注的板块列表」/「我创建的板块的粉丝列表」分页查询。
 * 分页参数继承自 {@link PageRequest}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BoardFollowPageDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;
}
