package com.ruwei.social.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.dto.UserFollowOrFansPageDTO;
import com.ruwei.model.entity.UserFollow;
import com.ruwei.model.vo.UserVO;


/**
* @author Administrator
* @description 针对表【user_follow(关注关系表)】的数据库操作Service
* @createDate 2026-07-29 17:56:04
*/
public interface UserFollowService extends IService<UserFollow> {

    /**
     * 关注用户
     * <p>入参可传 id（内部主键）或 userId（对外编码），二选一；库内统一以内部 id 存储关系。</p>
     * @param id     对方内部主键（优先使用）
     * @param userId 对方对外编码（与 id 二选一）
     */
    void followUser(Long id, Long userId);

    /**
     * 取消关注
     * <p>入参可传 id（内部主键）或 userId（对外编码），二选一；库内统一以内部 id 存储关系。</p>
     * @param id     对方内部主键（优先使用）
     * @param userId 对方对外编码（与 id 二选一）
     */
    void cancelFollowUser(Long id, Long userId);


    /**
     * 获取关注列表（分页，按关注时间倒序）
     * @return 关注用户的分页结果（UserVO）
     */
    IPage<UserVO> getFollowUserList(UserFollowOrFansPageDTO userFollowOrFansPageDTO);


    /**
     * 获取粉丝列表（分页，按关注时间倒序）
     * @return 粉丝用户的分页结果（UserVO）
     */
    IPage<UserVO> getFansUserList(UserFollowOrFansPageDTO userFollowOrFansPageDTO);

    /**
     * 当前登录用户是否关注了目标用户（读 Redis 热索引，键缺失自动回源重建）。
     * @param id     目标用户内部主键（与 userId 二选一）
     * @param userId 目标用户对外编码（与 id 二选一）
     */
    boolean isFollowed(Long id, Long userId);

    /**
     * 目标用户是否关注了当前登录用户（即目标用户是不是我的粉丝）。
     * @param id     目标用户内部主键（与 userId 二选一）
     * @param userId 目标用户对外编码（与 id 二选一）
     */
    boolean isFans(Long id, Long userId);

    /**
     * 与目标用户是否互相关注（= 我关注了他 且 他关注了我）。
     * @param id     目标用户内部主键（与 userId 二选一）
     * @param userId 目标用户对外编码（与 id 二选一）
     */
    boolean isMutual(Long id, Long userId);

}
