package com.ruwei.post.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mybatis.QueryWrapperUtils;
import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.BoardQueryDTO;
import com.ruwei.model.dto.BoardUpdateDTO;
import com.ruwei.model.dto.CreateBoardDTO;
import com.ruwei.model.entity.Board;
import com.ruwei.post.mapper.BoardMapper;
import com.ruwei.post.service.BoardService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;


/**
* @author Administrator
* @description 针对表【board(贴吧板块表)】的数据库操作Service实现
* @createDate 2026-08-03 09:33:16
*/
@Service
public class BoardServiceImpl extends ServiceImpl<BoardMapper, Board>
    implements BoardService {

    @DubboReference
    private InnerUserService innerUserService;

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;


    /**
     * 创建贴吧
     * @param createBoardDTO
     * @return
     */
    @Override
    public Board createBoard(CreateBoardDTO createBoardDTO) {
        //校验参数
        String name = createBoardDTO.getName();
        String slug = createBoardDTO.getSlug();
        String description = createBoardDTO.getDescription();

        ThrowUtils.throwIf(BeanUtil.isEmpty(createBoardDTO)||StrUtil.isBlank(name)|| StrUtil.isBlank(slug), ErrorCode.PARAMS_ERROR,"贴吧名字和标识不能为空");
        long loginId = StpUtil.getLoginIdAsLong();

        //参数合法校验
        ThrowUtils.throwIf(name.length() < 2 || name.length() > 12,
                ErrorCode.PARAMS_ERROR, "吧名长度必须为2~12位");
        ThrowUtils.throwIf(name.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "吧名不能全为数字");

        ThrowUtils.throwIf(slug.length() < 6 || slug.length() > 12,
                ErrorCode.PARAMS_ERROR, "唯一标识长度必须为6~12位");
        // 只能包含字母和数字，不能含汉字或特殊字符
        ThrowUtils.throwIf(!slug.matches("^[a-zA-Z0-9]+$"),
                ErrorCode.PARAMS_ERROR, "唯一标识只能包含字母和数字，不能包含汉字或特殊字符");
        // 必须同时包含字母和数字（不能纯字母或纯数字）
        ThrowUtils.throwIf(!(slug.matches(".*[a-zA-Z].*") && slug.matches(".*\\d.*")),
                ErrorCode.PARAMS_ERROR, "唯一标识必须同时包含字母和数字");

        // 简介为选填，非空时最多 200 字
        if (StrUtil.isNotBlank(description)) {
            ThrowUtils.throwIf(description.length() > 200,
                    ErrorCode.PARAMS_ERROR, "简介最多200字");
        }

        // ===== 敏感词/违禁词检查 =====
        // 仅对“用户可自由输入的展示字段”做检查，且只在字段非空时执行：
        // 命中任意敏感词（替换/拦截/审核）即由 checkStrict 抛 PARAMS_ERROR 拒绝本次创建。
        if (StrUtil.isNotBlank(name)) {
            sensitiveWordFilter.checkStrict(name, "吧名");
        }
        if (StrUtil.isNotBlank(description)) {
            sensitiveWordFilter.checkStrict(description, "简介");
        }

        boolean exists = lambdaQuery().eq(Board::getName, name).exists();
        ThrowUtils.throwIf(exists,ErrorCode.PARAMS_ERROR,"改吧名已经存在");

        exists = lambdaQuery().eq(Board::getSlug, slug).exists();
        ThrowUtils.throwIf(exists,ErrorCode.PARAMS_ERROR,"唯一标识已经存在");

        Long count = lambdaQuery().eq(Board::getCreatorId, loginId).count();
        ThrowUtils.throwIf(count>3,ErrorCode.OPERATION_ERROR,"最多创建三个贴吧");

        Board board = BeanUtil.copyProperties(createBoardDTO, Board.class);
        board.setCreatorId(loginId);
        boolean save = save(board);
        ThrowUtils.throwIf(!save,ErrorCode.OPERATION_ERROR,"创建失败");


        return board;
    }

    /**
     * 分页获取板块列表（支持按名称模糊搜索，按关注数倒序）
     * @param queryDTO 查询条件（keyword 对应 name 字段，支持分页 current/pageSize 和排序 sortField/sortOrder）
     * @return 板块分页结果
     */
    @Override
    public IPage<Board> listBoards(BoardQueryDTO queryDTO) {
        // 构造 QueryWrapper（含模糊匹配和排序）
        QueryWrapper<Board> queryWrapper = QueryWrapperUtils.getBoardQueryWrapper(queryDTO);
        // 分页查询：current 和 pageSize 由 queryDTO 传入（默认 1/10）
        Page<Board> page = new Page<>(queryDTO.getCurrent(), queryDTO.getPageSize());
        return this.page(page, queryWrapper);
    }


    /**
     * 吧主删除自己创建的板块（逻辑删除：isDelete=1，数据保留可恢复）
     * @param id 板块内部主键
     */
    @Override
    public void deleteBoardByCreator(Long id) {
        long loginId = StpUtil.getLoginIdAsLong();

        // 校验板块存在
        Board board = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(board), ErrorCode.NOT_FOUND_ERROR, "板块不存在");

        // 仅创建者可操作/管理员
        ThrowUtils.throwIf(loginId!=board.getCreatorId()&&!innerUserService.isAdmin(loginId),
                ErrorCode.NO_AUTH_ERROR, "只能删除自己创建的板块");

        boolean result = removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除板块失败");
    }

    /**
     * 管理员封禁板块（status=2，可恢复，不触发逻辑删除）
     * @param id 板块内部主键
     */
    @Override
    public void banBoardByAdmin(Long id) {
        // 仅管理员可操作
        Boolean isAdmin = innerUserService.isAdmin(StpUtil.getLoginIdAsLong());
        ThrowUtils.throwIf(!isAdmin, ErrorCode.NO_AUTH_ERROR, "只有管理员才能封禁板块");

        // 校验板块存在
        Board board = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(board), ErrorCode.NOT_FOUND_ERROR, "板块不存在");

        // 封禁：status=2（可恢复）
        boolean result = lambdaUpdate()
                .eq(Board::getId, id)
                .set(Board::getStatus, 2)
                .update();
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "封禁板块失败");
    }

    /**
     * 管理员彻底删除板块（物理删除：DELETE FROM，数据不可恢复）
     * @param id 板块内部主键
     */
    @Override
    public void deleteBoardByAdmin(Long id) {
        // 仅管理员可操作
        Boolean isAdmin = innerUserService.isAdmin(StpUtil.getLoginIdAsLong());
        ThrowUtils.throwIf(!isAdmin, ErrorCode.NO_AUTH_ERROR, "只有管理员才能删除板块");

        // 校验板块存在
        Board board = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(board), ErrorCode.NOT_FOUND_ERROR, "板块不存在");

        // 物理删除：数据不可恢复
        boolean result = removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除板块失败");
    }

    /**
     * 更改贴吧信息
     * @param boardUpdateDTO 贴吧更新参数（含 id、吧名、简介等）
     */
    @Override
    public void updateBoard(BoardUpdateDTO boardUpdateDTO) {
        // 1.入参进行校验
        ThrowUtils.throwIf(BeanUtil.isEmpty(boardUpdateDTO), ErrorCode.PARAMS_ERROR, "请求参数不能为空");

        Boolean isAdmin = innerUserService.isAdmin(StpUtil.getLoginIdAsLong());
        long loginId = StpUtil.getLoginIdAsLong();
        // 权限校验：仅创建者（吧主）或管理员可修改
        ThrowUtils.throwIf(loginId != boardUpdateDTO.getCreatorId() && !isAdmin,
                ErrorCode.NO_AUTH_ERROR, "只能管理员和创建本吧主修改");

        // 关键字段非空校验
        ThrowUtils.throwIf(boardUpdateDTO.getId() == null, ErrorCode.PARAMS_ERROR, "板块ID不能为空");
        String name = boardUpdateDTO.getName();
        ThrowUtils.throwIf(StrUtil.isBlank(name), ErrorCode.PARAMS_ERROR, "板块名称不能为空");

        // 2.查看板块是否存在
        Long id = boardUpdateDTO.getId();
        Board oldBoard = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(oldBoard), ErrorCode.NOT_FOUND_ERROR, "修改失败,该吧不存在");

        //3.校验后对输入的参数进行校验
        ThrowUtils.throwIf(name.length() < 2 || name.length() > 12,
                ErrorCode.PARAMS_ERROR, "吧名长度必须为2~12位");
        ThrowUtils.throwIf(name.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "吧名不能全为数字");

        // 简介为选填，非空时最多 200 字
        String description = boardUpdateDTO.getDescription();
        if (StrUtil.isNotBlank(description)) {
            ThrowUtils.throwIf(description.length() > 200,
                    ErrorCode.PARAMS_ERROR, "简介最多200字");
        }

        //4.敏感词扫描与替换（确保入库不含违规敏感词）
        name = scrubText(name, "吧名");
        boardUpdateDTO.setName(name);
        if (StrUtil.isNotBlank(description)) {
            description = scrubText(description, "简介");
            boardUpdateDTO.setDescription(description);
        }

        //5判断传递板块的名字是否是和之前的一样
        boolean result;
        if (oldBoard.getName().equals(name)) {
            //一样直接修改
            result = updateById(BeanUtil.copyProperties(boardUpdateDTO, Board.class));
        } else {
            //不一样需要查询后进修改
            boolean exists = lambdaQuery().eq(Board::getName, name).exists();
            ThrowUtils.throwIf(exists, ErrorCode.PARAMS_ERROR, "改吧名已经存在");
            result = updateById(BeanUtil.copyProperties(boardUpdateDTO, Board.class));
        }
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新失败");
    }

    /**
     * 敏感词扫描与替换：命中拦截词直接拒绝；命中替换词脱敏为 *** 后返回；审核词暂无审核流，按放行处理。
     *
     * @param text 待处理文本
     * @param fieldName 字段中文名，用于异常提示
     * @return 处理后的文本（替换词已脱敏）
     */
    private String scrubText(String text, String fieldName) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        SensitiveWordFilter.FilterResult fr = sensitiveWordFilter.filter(text);
        // 拦截词：直接拒绝更新
        ThrowUtils.throwIf(fr.action == SensitiveWordFilter.SensitiveAction.INTERCEPT,
                ErrorCode.PARAMS_ERROR, fieldName + "包含敏感或违规内容，请修改后重试");
        // 替换词：脱敏后返回（***）
        if (fr.action == SensitiveWordFilter.SensitiveAction.REPLACED) {
            return fr.processedText;
        }
        // PASS / REVIEW：保留原文（REVIEW 无审核流，当前按放行处理）
        return text;
    }



}




