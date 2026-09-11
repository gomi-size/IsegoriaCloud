package com.ruwei.domain.Enum;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 帖子生命周期状态枚举
 * 对应 post 表 status 列：1已发布 2草稿 3审核中 4下架
 *
 * <p>前端传<b>中文文字</b>（如 "已发布"），后端用 {@link #codeOfText(String)} 转成整数落库；
 * 读库后可用 {@link #textOfCode(Integer)} 还原文字给前端展示。</p>
 */
@Getter
@AllArgsConstructor
public enum PostStatusEnum {

    PUBLISHED(1, "已发布"),
    DRAFT(2, "草稿"),
    REVIEWING(3, "审核中"),
    OFFLINE(4, "下架");

    /** 落库编码 */
    private final int code;

    /** 前端传递/展示的文字 */
    private final String text;

    /**
     * 根据编码获取枚举实例
     */
    public static PostStatusEnum getByCode(int code) {
        for (PostStatusEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    /**
     * 根据前端文字获取枚举实例（找不到返回 null）
     */
    public static PostStatusEnum getByText(String text) {
        if (text == null) {
            return null;
        }
        for (PostStatusEnum e : values()) {
            if (e.text.equals(text)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 判断编码是否合法
     */
    public static boolean isValid(int code) {
        return getByCode(code) != null;
    }

    /**
     * 判断传入编码是否为当前枚举项（null 安全，避免 Integer 拆箱 NPE）。
     * <p>用法：{@code PostStatusEnum.DRAFT.matches(post.getStatus())}</p>
     */
    public boolean matches(Integer code) {
        return code != null && this.code == code;
    }

    /**
     * 前端文字 → 落库编码；非法/未知文字返回 null
     */
    public static Integer codeOfText(String text) {
        PostStatusEnum e = getByText(text);
        return e == null ? null : e.code;
    }

    /**
     * 落库编码 → 前端文字；未知编码返回 null
     */
    public static String textOfCode(Integer code) {
        if (code == null) {
            return null;
        }
        PostStatusEnum e = getByCode(code);
        return e == null ? null : e.text;
    }
}
