package com.ruwei.post.service;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.dto.BoardQueryDTO;
import com.ruwei.model.dto.BoardUpdateDTO;
import com.ruwei.model.dto.CreateBoardDTO;
import com.ruwei.model.entity.Board;

/**
* @author Administrator
* @description 针对表【board(贴吧板块表)】的数据库操作Service
* @createDate 2026-08-03 09:33:16
*/
public interface BoardService extends IService<Board> {

    /**
     * 创建贴吧
     * @param createBoardDTO
     * @return
     */
    Board createBoard(CreateBoardDTO createBoardDTO);

    /**
     * 更改贴吧
     * @param boardUpdateDTO
     */
    void updateBoard(BoardUpdateDTO boardUpdateDTO);

    /**
     * 分页获取板块列表（支持按名称模糊搜索，按关注数倒序）
     * @param queryDTO 查询条件（keyword 对应 name 字段，支持分页 current/pageSize 和排序 sortField/sortOrder）
     * @return 板块分页结果
     */
    IPage<Board> listBoards(BoardQueryDTO queryDTO);


    /**
     * 吧主删除自己创建的板块（逻辑删除：isDelete=1，数据保留可恢复）
     * @param id 板块内部主键
     */
    void deleteBoardByCreator(Long id);

    /**
     * 管理员封禁板块（status=2，可恢复，不触发逻辑删除）
     * @param id 板块内部主键
     */
    void banBoardByAdmin(Long id);

    /**
     * 管理员彻底删除板块（物理删除：DELETE FROM，数据不可恢复）
     * @param id 板块内部主键
     */
    void deleteBoardByAdmin(Long id);
}
