package com.ruwei.post.service;

import com.ruwei.model.dto.SensitiveWordAddDTO;
import com.ruwei.model.entity.SensitiveWord;
import com.ruwei.model.vo.SensitiveWordVO;

import java.util.List;

/**
 * 敏感词业务接口。
 *
 * <p>所有写操作（新增/批量新增/删除）在落库成功后都会触发本服务词表即时重载
 * （{@code SensitiveWordFilter.setWords}），将内存中的 DFA Trie 热刷新为最新词库，
 * 保证过滤器与数据库一致、无需重启；其他服务的词表靠各自 SensitiveWordLoader 定时拉取。</p>
 */
public interface SensitiveWordService {

    /**
     * 加载全量词表实体（专供 {@code SensitiveWordLoader} 灌入过滤器）。
     *
     * <p>与 {@link #listAll()} 的区别：本方法返回<b>实体</b>（过滤器需要 word/action 原始字段），
     * {@code listAll()} 返回 VO（对管理端展示，不下发实体）。仅限 post 服务模块内部调用，
     * 不经 Controller 暴露。</p>
     *
     * @return 全量敏感词实体列表（表空返回空列表，绝不为 null）
     */
    List<SensitiveWord> loadAllWords();

    /**
     * 新增单个敏感词。
     *
     * @param dto 敏感词入参（word 必填，category/action 可缺省取默认）
     * @return 写入成功返回 {@code true}，否则 {@code false}
     */
    boolean add(SensitiveWordAddDTO dto);

    /**
     * 批量新增敏感词（一次传多组）。
     *
     * @param dtos 敏感词入参列表，每项可单独指定 category / action
     * @return 成功写入的条数（已跳过空/空白项）
     */
    int addBatch(List<SensitiveWordAddDTO> dtos);

    /**
     * 根据主键删除敏感词。
     *
     * @param id 敏感词记录主键
     * @return 删除成功返回 {@code true}，否则 {@code false}
     */
    boolean deleteWord(Long id);

    /**
     * 查询全部敏感词列表（按数据库全量返回，转为 VO 不下发实体）。
     *
     * @return 敏感词 VO 列表
     */
    List<SensitiveWordVO> listAll();
}
