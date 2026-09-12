package com.ruwei.post.assembler;

import com.ruwei.model.entity.Board;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.post.service.BoardService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 帖子卡片 VO 的板块信息批量装配组件。
 *
 * <p><b>职责</b>：为 {@link PostBrowseVO} 列表原地填充板块对象
 * {@link PostBrowseVO.BoardBrief}（{@code board.id} / {@code name} / {@code slug} / {@code icon}，
 * 来源为 board 表，不冗余存储于 post 表）。与作者信息（userMap 批量装配）同理，
 * 一次收集本页全部非空 {@code boardId} 后调用 {@code listByIds} 批量查询，
 * <b>避免逐帖查询 board 造成 N+1</b>。</p>
 *
 * <p><b>兼容期双写</b>：同时填充已废弃的平铺字段 {@code boardName} / {@code boardSlug}
 * （存量前端直接读这两个字段）。待前端全部迁移到 {@code board} 对象后，
 * 删除本类末尾的平铺字段写入与 VO 上的两个字段即可。</p>
 *
 * <p><b>适用范围</b>：所有产出 PostBrowseVO 列表的装配链路 —— 帖子列表
 * （PostServiceImpl）、推荐流（RecServiceImpl）、ES 搜索（EsSearchService）等，统一注入本组件
 * 各接入一行调用即可，未来新增装配点直接复用。</p>
 *
 * <p><b>边界</b>：{@code boardId} 为 null（无板块帖）或板块已被逻辑删除（MyBatis-Plus
 * {@code @TableLogic} 使 {@code listByIds} 自动排除）时，对应 VO 保持 {@code board} 为 null，
 * 前端据此不渲染板块入口，调用方无需额外判空。</p>
 *
 * @author ruwei
 */
@Component
public class BoardBriefFiller {

    @Resource
    private BoardService boardService;

    /**
     * 批量填充板块简要信息（原地修改入参列表，无返回值）。
     *
     * <p>实现：① 收集入参中全部非空且去重的 {@code boardId}；② 集合为空（本页无板块帖）
     * 直接返回，零 DB 开销；③ {@code listByIds} 一次批量查询（逻辑删除自动过滤）；
     * ④ 按 id 建索引后原地写入 {@code board}（BoardBrief）与兼容用的
     * {@code boardName} / {@code boardSlug}。</p>
     *
     * @param voList 已组装的 PostBrowseVO 列表（可空；原地填充，不新建）
     */
    public void fillBoardBrief(List<PostBrowseVO> voList) {
        if (voList == null || voList.isEmpty()) {
            return;
        }
        // ① 收集本页去重的非空板块 id
        List<Long> boardIds = voList.stream()
                .map(PostBrowseVO::getBoardId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (boardIds.isEmpty()) {
            return;
        }
        // ③ 批量查询：逻辑删除板块已被 @TableLogic 过滤，查不到则对应 VO 不填充
        List<Board> boards = boardService.listByIds(boardIds);
        if (boards.isEmpty()) {
            return;
        }
        Map<Long, Board> boardMap = boards.stream()
                .collect(Collectors.toMap(Board::getId, Function.identity(), (a, b) -> a));
        // ④ 原地填充
        voList.forEach(vo -> {
            if (vo.getBoardId() == null) {
                return;
            }
            Board board = boardMap.get(vo.getBoardId());
            if (board != null) {
                // ④ 新版：结构化板块对象（含 id/name/slug/icon，前端按 slug 跳转）
                vo.setBoard(PostBrowseVO.BoardBrief.of(board));
                // ④ 兼容期：平铺字段（存量前端仍在读，迁移完成后移除）
                vo.setBoardName(board.getName());
                vo.setBoardSlug(board.getSlug());
            }
        });
    }
}
