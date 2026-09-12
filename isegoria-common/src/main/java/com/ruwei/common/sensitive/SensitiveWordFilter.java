package com.ruwei.common.sensitive;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.model.entity.SensitiveWord;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.WordTree;
import lombok.extern.slf4j.Slf4j;

/**
 * 敏感词过滤器（内存 DFA 实现 - 多维防护增强版，六服务共享）。
 *
 * <p>与旧单体版的差异：本类是<b>纯内存过滤器</b>，不再持有任何 Mapper、也不在启动时查库 ——
 * 词表由各服务自己的 {@code SensitiveWordLoader} 经 Dubbo 从 post 服务拉取后调用
 * {@link #setWords(List)} 灌入（post 服务本地则由 SensitiveWordService 落库后直接灌入）。</p>
 *
 * <p>词表按 {@code action} 拆分为三棵 Hutool WordTree：
 * 1=替换（命中替换为 *** 后发布） 2=拦截（直接拒绝） 3=审核（转送审）。</p>
 *
 * <p>引入双重文本检测机制（基础归一化 + 纯中文提取），有效抵御"澳s门s赌s场"等变种插字绕过。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 处置动作：替换成 *** 后发布 */
    public static final int ACTION_REPLACE = 1;
    /** 处置动作：直接拦截（拒绝发布） */
    public static final int ACTION_INTERCEPT = 2;
    /** 处置动作：进入审核队列，由调用方联动内容状态机 */
    public static final int ACTION_REVIEW = 3;

    /** 替换树：命中后替换为 *** */
    private volatile WordTree replaceTree = new WordTree();
    /** 拦截树：命中后直接拒绝 */
    private volatile WordTree interceptTree = new WordTree();
    /** 审核树：命中后转为送审 */
    private volatile WordTree reviewTree = new WordTree();

    /**
     * 用外部灌入的词表重建三棵 Trie（原子替换，刷新期间进行中的请求仍用旧树）。
     *
     * <p>调用时机：各服务 {@code SensitiveWordLoader} 启动拉取 + 每 5 分钟兜底刷新；
     * post 服务管理端增删词后本服务即时刷新。拉取失败时调用方 catch 后沿用旧词表。</p>
     *
     * @param words 全量敏感词（含 {@link SensitiveWord#getAction()}）；传 {@code null} 或空表 = 不过滤，
     *              与旧版"表空"行为一致
     */
    public void setWords(List<SensitiveWord> words) {
        List<SensitiveWord> all = (words == null) ? List.of() : words;
        WordTree r = new WordTree();
        WordTree i = new WordTree();
        WordTree v = new WordTree();
        for (SensitiveWord sw : all) {
            String w = StrUtil.trimToNull(sw.getWord());
            if (w == null) {
                continue;
            }
            String key = normalize(w);
            if (StrUtil.isBlank(key)) {
                continue;
            }
            int action = sw.getAction() == null ? ACTION_REPLACE : sw.getAction();
            switch (action) {
                case ACTION_INTERCEPT -> i.addWord(key);
                case ACTION_REVIEW -> v.addWord(key);
                default -> r.addWord(key);
            }
        }
        this.replaceTree = r;
        this.interceptTree = i;
        this.reviewTree = v;
        log.info("敏感词表装载完成，共 {} 条（替换/拦截/审核 已按 action 拆分）", all.size());
    }

    /**
     * 严格检查（用于用户资料等无审核流场景）。
     *
     * <p>采用<b>双重检测</b>：先对原文做基础归一化，再提取纯中文（剥离插入的字母/数字/符号），
     * 两个维度的文本只要命中任一棵树（替换 / 拦截 / 审核）即视为包含敏感或违规内容，
     * 抛出 {@code PARAMS_ERROR} 并附带字段名提示。空白文本直接放行（不做检查）。</p>
     *
     * @param text 待检查文本（如昵称、个性签名、所在地）
     * @param fieldName 字段中文名，用于异常提示，例如"昵称"
     */
    public void checkStrict(String text, String fieldName) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        String normalText = normalize(text);
        String pureChinese = extractPureChinese(normalText);
        boolean hit = isHitAnyTree(normalText) || (!pureChinese.isEmpty() && isHitAnyTree(pureChinese));
        ThrowUtils.throwIf(hit, ErrorCode.PARAMS_ERROR,
                fieldName + "包含敏感或违规内容，请修改后重试");
    }

    /**
     * 内容发布场景的过滤入口，返回结构化处置结果，由调用方联动业务状态机。
     *
     * <p>处置优先级：<b>拦截 &gt; 审核 &gt; 替换</b>——同一文本同时命中多类词时取最严处置。
     * 替换环节仅对归一化文本进行（原文本含干扰字符时替换定位性价比极低，高危词已被拦截）。</p>
     *
     * @param text 待过滤文本
     * @return 处置结果 {@link FilterResult}
     */
    public FilterResult filter(String text) {
        if (StrUtil.isBlank(text)) {
            return FilterResult.pass();
        }
        // 1. 基础归一化（去空格 + 全角转半角 + 小写）
        String normalText = normalize(text);
        // 2. 纯中文提取（去除所有字母、数字、符号，降维打击插字绕过）
        String pureChinese = extractPureChinese(normalText);

        // --- 拦截判断（最高优先级）---
        if (!interceptTree.matchAll(normalText).isEmpty()
                || (!pureChinese.isEmpty() && !interceptTree.matchAll(pureChinese).isEmpty())) {
            return FilterResult.intercept();
        }
        // --- 审核判断（次优先级）---
        if (!reviewTree.matchAll(normalText).isEmpty()
                || (!pureChinese.isEmpty() && !reviewTree.matchAll(pureChinese).isEmpty())) {
            return FilterResult.review();
        }
        // --- 替换判断（最低优先级，仅对归一化文本）---
        List<String> hits = replaceTree.matchAll(normalText);
        if (!hits.isEmpty()) {
            String replaced = normalText;
            for (String w : hits) {
                replaced = replaced.replace(w, "***");
            }
            return FilterResult.replaced(replaced);
        }
        return FilterResult.pass();
    }

    /**
     * 判断文本是否在任一棵树（拦截 / 审核 / 替换）中命中。
     *
     * @param text 待匹配文本（已归一化或已提取纯中文）
     * @return 命中任一棵树返回 {@code true}
     */
    private boolean isHitAnyTree(String text) {
        return !interceptTree.matchAll(text).isEmpty()
                || !reviewTree.matchAll(text).isEmpty()
                || !replaceTree.matchAll(text).isEmpty();
    }

    /**
     * 基础归一化：去空格 + 全角转半角 + 小写（对抗"敏 感 词"、全角字母等简单变体）。
     *
     * @param text 原始文本
     * @return 归一化后的文本
     */
    private String normalize(String text) {
        return Convert.toDBC(StrUtil.cleanBlank(text)).toLowerCase();
    }

    /**
     * 提取纯中文文本（仅保留汉字），把"澳s门s赌s场wud"降维为"澳门赌场"参与匹配。
     *
     * @param text 已归一化的文本
     * @return 仅含汉字的文本；无汉字时返回空字符串
     */
    private String extractPureChinese(String text) {
        return text.replaceAll("[^\\u4e00-\\u9fa5]", "");
    }

    /**
     * 处置动作枚举，对应 {@link FilterResult#action} 的取值。
     */
    public enum SensitiveAction {
        /** 放行 */
        PASS,
        /** 拦截（拒绝发布） */
        INTERCEPT,
        /** 进入审核 */
        REVIEW,
        /** 已替换后发布 */
        REPLACED
    }

    /**
     * 过滤结果封装：处置动作 + 脱敏后文本（仅 REPLACED 时非空）。
     */
    public static class FilterResult {
        /** 处置动作 */
        public final SensitiveAction action;
        /** 处理后的文本（替换动作时为脱敏文本，其余为 null） */
        public final String processedText;

        private FilterResult(SensitiveAction action, String processedText) {
            this.action = action;
            this.processedText = processedText;
        }

        /** 构造"放行"结果 */
        public static FilterResult pass() {
            return new FilterResult(SensitiveAction.PASS, null);
        }

        /** 构造"拦截"结果 */
        public static FilterResult intercept() {
            return new FilterResult(SensitiveAction.INTERCEPT, null);
        }

        /** 构造"送审"结果 */
        public static FilterResult review() {
            return new FilterResult(SensitiveAction.REVIEW, null);
        }

        /** 构造"替换后发布"结果，携带脱敏文本 */
        public static FilterResult replaced(String text) {
            return new FilterResult(SensitiveAction.REPLACED, text);
        }
    }
}