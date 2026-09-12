package com.ruwei.post.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.BusinessException;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.model.dto.SensitiveWordAddDTO;
import com.ruwei.model.entity.SensitiveWord;
import com.ruwei.model.vo.SensitiveWordVO;
import com.ruwei.post.mapper.SensitiveWordMapper;
import com.ruwei.post.service.SensitiveWordService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SensitiveWordServiceImpl extends ServiceImpl<SensitiveWordMapper, SensitiveWord>
        implements SensitiveWordService {

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    /**
     * 从本服务库中重载全量词表并热刷新内存 Trie。
     *
     * <p>post 是词表属主（sensitive_word 表在本域），CRUD 落库后直接重查本域数据
     * 调用 {@link SensitiveWordFilter#setWords(List)} 原子替换三棵 Trie；
     * 其他服务的词表则由各自 SensitiveWordLoader 定时经 Dubbo 拉取（时差 ≤5min，已知取舍）。</p>
     */
    private void reloadWords() {
        sensitiveWordFilter.setWords(list());
    }

    /**
     * 新增单个敏感词。
     *
     * <p>落库成功后调用 本服务即时刷新本地词表（reloadWords）；
     * 若词已存在（命中唯一约束 {@code uk_word}），转换为
     * {@code PARAMS_ERROR: 敏感词已存在} 业务异常。</p>
     *
     * @param dto 敏感词入参（word 必填）
     * @return 写入成功返回 {@code true}，否则 {@code false}
     */
    @Override
    public boolean add(SensitiveWordAddDTO dto) {
        SensitiveWord sw = new SensitiveWord();
        sw.setWord(dto.getWord());
        sw.setCategory(dto.getCategory() == null ? 1 : dto.getCategory());
        sw.setAction(dto.getAction() == null ? 1 : dto.getAction());
        try {
            boolean saved = save(sw);
            if (saved) {
                reloadWords();
            }
            return saved;
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "敏感词已存在");
        }
    }

    /**
     * 批量新增敏感词（一次传多组）。
     *
     * <p>自动跳过 {@code null} 或 word 为空白的项；使用 MyBatis-Plus {@code saveBatch}
     * 一次性落库，随后仅触发<b>一次</b> reloadWords() 刷新内存词库。
     * 若批次内或库中已存在重复 word（命中唯一约束），整体抛出
     * {@code PARAMS_ERROR: 存在重复或已存在的敏感词}。</p>
     *
     * @param dtos 敏感词入参列表，每项可单独指定 category / action
     * @return 成功写入的条数（已跳过空/空白项）
     */
    @Override
    public int addBatch(List<SensitiveWordAddDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return 0;
        }
        List<SensitiveWord> entities = new ArrayList<>();
        for (SensitiveWordAddDTO d : dtos) {
            if (d == null || d.getWord() == null || d.getWord().isBlank()) {
                continue;
            }
            SensitiveWord sw = new SensitiveWord();
            sw.setWord(d.getWord().trim());
            sw.setCategory(d.getCategory() == null ? 1 : d.getCategory());
            sw.setAction(d.getAction() == null ? 1 : d.getAction());
            entities.add(sw);
        }
        if (entities.isEmpty()) {
            return 0;
        }
        try {
            saveBatch(entities);
            reloadWords();
            return entities.size();
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "存在重复或已存在的敏感词");
        }
    }

    /**
     * 根据主键删除敏感词。
     *
     * <p>删除成功后调用 reloadWords() 热刷新内存词库。</p>
     *
     * @param id 敏感词记录主键
     * @return 删除成功返回 {@code true}，否则 {@code false}
     */
    @Override
    public boolean deleteWord(Long id) {
        boolean removed = removeById(id);
        if (removed) {
            reloadWords();
        }
        return removed;
    }

    /**
     * 加载全量词表实体（专供 SensitiveWordLoader 灌入过滤器）。
     *
     * @return 全量敏感词实体列表（表空返回空列表，绝不为 null）
     */
    @Override
    public List<SensitiveWord> loadAllWords() {
        return list();
    }

    /**
     * 查询全部敏感词列表（按数据库全量返回，复制为 VO 不下发实体）。
     *
     * @return 敏感词 VO 列表
     */
    @Override
    public List<SensitiveWordVO> listAll() {
        return list().stream()
                .map(sw -> BeanUtil.copyProperties(sw, SensitiveWordVO.class))
                .toList();
    }
}
