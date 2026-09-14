package com.ruwei.social.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;

import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.web.RateLimit;
import com.ruwei.model.dto.UserFollowOrFansPageDTO;
import com.ruwei.model.vo.UserVO;
import com.ruwei.social.service.UserFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 关注关系的控制类
 */
@SaCheckLogin
@RestController
@RequestMapping("/userFollow")
public class UserFollowController {
    @Resource
    private UserFollowService userFollowService;

    /**
     * 关注
     * 入参可传 id（内部主键）或 userId（对外编码），二选一即可，库内统一存内部 id
     */
    @PostMapping("/follow")
    @RateLimit(limit = 10, window = 1, prefix = "userFollow")
    public BaseResponse<String> followUser(Long id, Long userId){
        userFollowService.followUser(id, userId);
        return ResultUtils.success("关注成功");
    }

    /**
     * 取消关注
     * 入参可传 id（内部主键）或 userId（对外编码），二选一即可，库内统一存内部 id
     */
    @PostMapping("/cancelFollow")
    @RateLimit(limit = 10, window = 1, prefix = "userFollow")
    public BaseResponse<String> cancelFollowUser(Long id, Long userId){
        userFollowService.cancelFollowUser(id, userId);
        return ResultUtils.success("取消成功");
    }
    /**
     * 查看关注列表（分页）
     */
    @PostMapping("/getFollowUserList")
    public BaseResponse<IPage<UserVO>> getFollowUserList(@RequestBody UserFollowOrFansPageDTO userFollowOrFansPageDTO){

       IPage<UserVO> page = userFollowService.getFollowUserList(userFollowOrFansPageDTO);
       return ResultUtils.success(page);
    }

    /**
     * 查看粉丝列表（分页）
     */
    @PostMapping("/getFansUserList")
    public BaseResponse<IPage<UserVO>> getFansUserList(@RequestBody  UserFollowOrFansPageDTO userFollowOrFansPageDTO){
        IPage<UserVO> page = userFollowService.getFansUserList(userFollowOrFansPageDTO);
        return ResultUtils.success(page);
    }

    /**
     * 查询当前登录用户与目标用户的关系（读 Redis 热索引，键缺失自动回源重建）
     * 返回：isFollowed(我是否关注了他) / isFans(他是否关注了我) / isMutual(是否互关)
     * 入参可传 id（内部主键）或 userId（对外编码），二选一即可
     */
    @PostMapping("/getRelation")
    public BaseResponse<Map<String, Boolean>> getRelation(Long id, Long userId){
        Map<String, Boolean> relation = new LinkedHashMap<>();
        relation.put("isFollowed", userFollowService.isFollowed(id, userId));
        relation.put("isFans", userFollowService.isFans(id, userId));
        relation.put("isMutual", userFollowService.isMutual(id, userId));
        return ResultUtils.success(relation);
    }
}
