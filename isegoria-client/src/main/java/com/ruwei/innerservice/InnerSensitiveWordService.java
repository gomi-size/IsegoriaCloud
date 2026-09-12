package com.ruwei.innerservice;

import java.util.List;

import com.ruwei.model.entity.SensitiveWord;

/**
 * post 服务提供（词表全量；消费方本地缓存 + 定时刷新）。
 *
 * <p>注意：返回的是<b>实体列表而非字符串列表</b> —— 消费方的 SensitiveWordFilter
 * 需要按 {@code action}（1 替换 / 2 拦截 / 3 审核）重建三棵 Trie，
 * 纯 {@code List<String>} 会丢掉处置动作、导致过滤语义退化。</p>
 */
public interface InnerSensitiveWordService {

    /**
     * 返回全量敏感词（含 word / action）。
     *
     * @return 敏感词实体列表；表为空时返回空列表
     */
    List<SensitiveWord> getAllWords();
}