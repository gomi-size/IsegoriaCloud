package com.ruwei.domain.Enum;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 是否管理员枚举
 * 对应 user 表 admin 列：TINYINT(1) NOT NULL DEFAULT 0
 */
@Getter
@AllArgsConstructor
public enum AdminEnum {

    User(0, "普通用户"),
    Admin(1, "管理员");

    /** 状态码 */
    private final int code;

    /** 状态描述 */
    private final String desc;

    /**
     * 根据状态码获取枚举实例
     * @param code 状态码
     * @return 对应的枚举值，未匹配返回 null
     */
    public static AdminEnum getByCode(int code) {
        for (AdminEnum admin : values()) {
            if (admin.code == code) {
                return admin;
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

    /**
     * 判断给定的状态码是否为管理员
     */
    public static boolean isAdmin(int code) {
        return Admin.code == code;
    }
}
