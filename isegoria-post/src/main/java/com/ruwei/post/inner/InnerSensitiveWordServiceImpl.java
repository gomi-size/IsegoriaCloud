package com.ruwei.post.inner;

import java.util.List;

import org.apache.dubbo.config.annotation.DubboService;

import com.ruwei.innerservice.InnerSensitiveWordService;
import com.ruwei.model.entity.SensitiveWord;
import com.ruwei.post.service.SensitiveWordService;

import jakarta.annotation.Resource;

/**
 * 敏感词契约的 Dubbo provider（供 user / interaction / social / notify / rec 拉取词表）。
 *
 * <p>post 是词表属主：本实现直接返回本域 sensitive_word 表全量数据（含 action），
 * 消费方据此重建"替换 / 拦截 / 审核"三棵 Trie；消费端本地缓存 + 5 分钟定时刷新，
 * post 管理端增删词后其他服务最迟 5 分钟生效（已知取舍）。</p>
 *
 * @author ruwei
 */
@DubboService
public class InnerSensitiveWordServiceImpl implements InnerSensitiveWordService {

    @Resource
    private SensitiveWordService sensitiveWordService;

    @Override
    public List<SensitiveWord> getAllWords() {
        // 全量返回（含 word / action）；词表量级小，无需分页
        return sensitiveWordService.loadAllWords();
    }
}
