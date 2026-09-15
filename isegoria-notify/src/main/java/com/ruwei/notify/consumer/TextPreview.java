package com.ruwei.notify.consumer;

import cn.hutool.core.util.StrUtil;

/**
 * 通知文案的内容摘要截断工具（评论 / 回复通知共用）。
 *
 * @author ruwei
 */
final class TextPreview {

    /** 摘要最大长度（超出截断并追加省略号） */
    private static final int MAX_LENGTH = 50;

    private TextPreview() {
    }

    /**
     * 截断为通知卡片上可展示的一行摘要。
     *
     * @param text 原始内容（可空）
     * @return 摘要；空白输入返回空串（非 null，便于直接拼文案）
     */
    static String of(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String plain = StrUtil.trim(text);
        return plain.length() > MAX_LENGTH
                ? StrUtil.sub(plain, 0, MAX_LENGTH) + "…"
                : plain;
    }
}