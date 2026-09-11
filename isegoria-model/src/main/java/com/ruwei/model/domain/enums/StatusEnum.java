package com.ruwei.domain.Enum;

import lombok.AllArgsConstructor;
import lombok.Getter;


/**
 * 用户/记录状态枚举
 */
@Getter
@AllArgsConstructor
public enum StatusEnum {

    NORMAL(1, "正常"),
    DISABLED(2, "禁用"),
    CANCELLED(3, "注销");

    /** 状态码 */
    private final int code;

    /** 状态描述 */
    private final String desc;

    /**
     * 根据状态码获取枚举实例
     * @param code 状态码
     * @return 对应的枚举值，未匹配返回 null
     */
    public static StatusEnum getByCode(int code) {
        for (StatusEnum status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * 判断给定的状态码是否有效
     */
    public static boolean isValid(int code) {
        return getByCode(code) != null;
    }
}