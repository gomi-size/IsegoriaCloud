package com.ruwei.social.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.dto.BoardFollowPageDTO;
import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.BoardFollow;
import com.ruwei.model.vo.UserVO;


/**
* @author Administrator
* @description 针对表【board_follow(用户关注板块表)】的数据库操作Service
* @createDate 2026-08-04 14:11:16
*
* <p><b>ID 体系约定：</b>本表 {@code userId} 列存储当前登录用户的<b>内部主键 id</b>
* （即 Sa-Token 的 loginId，雪花 ASSIGN_ID），与 user_follow / notification 等关系表
* 统一内部 id 的约定一致；{@code boardId} 为板块内部主键。对外入参仅需板块内部主键 boardId，
* 关注者一律取自登录态，不依赖前端传参。</p>
*/
public interface BoardFollowService extends IService<BoardFollow> {

    /**
     * 关注板块（幂等状态机：无记录→新增 status=1；曾取消→恢复为 1；已关注→拒绝重复）。
     * 板块关注数 +1，成功后发布板块关注事件通知吧主。
     * @param boardId 板块内部主键
     */
    void followBoard(Long boardId);

    /**
     * 取消关注板块（幂等状态机：关注中 status=1→置为 2 软取消保留历史；已取消→拒绝重复）。
     * 板块关注数 -1，取关不发布关注通知。
     * @param boardId 板块内部主键
     */
    void cancelFollowBoard(Long boardId);

    /**
     * 当前登录用户是否已关注该板块（仅统计 status=1 关注中，已取消不算关注）。
     * @param boardId 板块内部主键
     * @return 是否已关注
     */
    Boolean isFollowed(Long boardId);

    /**
     * 当前登录用户关注的板块列表（分页，按关注时间倒序）。
     * @param dto 分页参数（current / pageSize）
     * @return 已关注板块的分页结果（Board）
     */
    IPage<Board> getFollowBoardList(BoardFollowPageDTO dto);

    /**
     * 当前登录用户创建的板块的粉丝列表（分页，按关注时间倒序）。
     * @param dto 分页参数（current / pageSize）
     * @return 粉丝用户的分页结果（UserVO）
     */
    IPage<UserVO> getFansBoardList(BoardFollowPageDTO dto);
}
