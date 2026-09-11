package com.ruwei.domain.dto;

import com.ruwei.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 用户查询条件（入参 DTO）。
 *
 * <p>用于后台用户列表 / 搜索等场景，配合 {@code QueryWrapperUtils.getUserQueryWrapper} 使用。
 * 分页/排序参数继承自 {@link PageRequest}。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *     <li>{@link #id}：内部主键（雪花 ASSIGN_ID），精确匹配</li>
 *     <li>{@link #userId}：对外展示的唯一编码，精确匹配</li>
 *     <li>{@link #username}：登录名（手机或邮箱），模糊匹配</li>
 *     <li>{@link #nickname}：昵称，模糊匹配</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 内部主键 id（雪花 ASSIGN_ID），精确匹配
     */
    private Long id;

    /**
     * 对外展示的唯一编码，精确匹配
     */
    private Long userId;

    /**
     * 登录名（手机或邮箱），模糊匹配
     */
    private String username;

    /**
     * 昵称，模糊匹配
     */
    private String nickname;
}
