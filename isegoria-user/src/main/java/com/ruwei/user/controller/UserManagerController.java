package com.ruwei.user.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mybatis.QueryWrapperUtils;
import com.ruwei.common.web.RateLimit;
import com.ruwei.common.web.RateLimitDimension;
import com.ruwei.model.dto.UserEditDTO;
import com.ruwei.model.dto.UserLoginDTO;
import com.ruwei.model.dto.UserQueryDTO;
import com.ruwei.model.dto.UserRegisterDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.vo.UserVO;
import com.ruwei.user.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 用户的基础管理
 */
@RestController
@RequestMapping("/admin/user")
public class UserManagerController {

    @Resource
    private UserService userService;

    /**
     * 用户注册
     * @param userRegisterDTO
     * @return
     */
    @PostMapping("/register")
    @RateLimit(dimension = RateLimitDimension.IP, limit = 10, window = 600, prefix = "register")
    public BaseResponse<String> userRegister(@RequestBody UserRegisterDTO userRegisterDTO){

        User user= userService.userRegister(userRegisterDTO);
        StpUtil.login(user.getId());

        return ResultUtils.success("注册成功");
    }

    /**
     * 管理员登录
     * @param userLogin
     * @return
     */
    @PostMapping("/login")
    @RateLimit(dimension = RateLimitDimension.IP, limit = 10, window = 600, prefix = "adminLogin")
    public BaseResponse<UserVO> userLogin(@RequestBody UserLoginDTO userLogin) {
        User user = userService.userLogin(userLogin);
        StpUtil.login(user.getId());
        Boolean admin = userService.isAdmin(user.getId());
        ThrowUtils.throwIf(!admin, ErrorCode.NO_AUTH_ERROR,"只能是管理员登录");
        UserVO userVO = BeanUtil.copyProperties(user, UserVO.class);
        return ResultUtils.success(userVO);
    }

    /**
     * 用户退出登录（需登录）
     * @return
     */
    @SaCheckLogin
    @PostMapping("/out")
    public BaseResponse<String> userOut(){

        StpUtil.logout();

        return ResultUtils.success("成功退出");
    }

    /**
     * 用户注销（需登录，且只能注销自己）
     * @return
     */
    @SaCheckLogin
    @PostMapping("/cancel")
    public BaseResponse<String> userCancel(){
        Long userId = StpUtil.getLoginIdAsLong();

        User user = userService.getById(userId);

        ThrowUtils.throwIf(BeanUtil.isEmpty(user), ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        boolean result = userService.removeById(userId);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "用户注销失败");

        // 注销成功后，最后再清掉登录态
        StpUtil.logout();

        return ResultUtils.success("用户注销成功");
    }

    /**
     * 当前登录用户获取自己详情
     */
    @GetMapping("/userInfo")
    public BaseResponse<UserVO> getUserVOInfo(){
        // @SaCheckLogin 已保证登录态
        Long id = StpUtil.getLoginIdAsLong();

        return ResultUtils.success(BeanUtil.copyProperties(userService.getById(id),UserVO.class));
    }

    /**
     * 当前登录用户获取别人的详情详情
     * <p>入参兼容<b>对外编码 userId 与内部主键 id</b>：他人主页可传对外编码；
     * 帖子作者/关注列表等场景前端拿到的是内部雪花 id，同样可直接传入。</p>
     */
    @GetMapping("/otherUserInfo")
    @SaIgnore
    public BaseResponse<UserVO> getOtherUserVOInfo(Long userId){
        UserVO userVO =userService.getOtherUserVOInfo(userId);
        return ResultUtils.success(userVO);
    }

    /**
     * 管理员：修改指定用户的状态（禁用 / 启用 / 注销）—— 对应“用户状态权”
     * 仅管理员可访问 —— @SaCheckRole("admin")
     */
    @SaCheckRole("admin")
    @PostMapping("/status")
    public BaseResponse<String> updateUserStatus(@RequestParam Long Id,
                                                 @RequestParam Integer status) {
        ThrowUtils.throwIf(Id==null||status==null,ErrorCode.PARAMS_ERROR,"有一个为空");
        boolean result = userService.updateUserStatus(Id, status);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "修改用户状态失败");
        return ResultUtils.success("修改成功");
    }

    /**
     * 管理员查看所有用户列表
     * @return
     */
    @SaCheckRole("admin")
    @PostMapping("/list")
    public BaseResponse<IPage<User>> listAllUsers(@RequestBody UserQueryDTO userQueryDTO) {
        QueryWrapper<User> userQueryWrapper = QueryWrapperUtils.getUserQueryWrapper(userQueryDTO);
        IPage<User> userPage = userService.page(new Page<>(userQueryDTO.getCurrent(), userQueryDTO.getPageSize()), userQueryWrapper);
        userPage.convert(user -> {
            user.setPassword("*****");
            return user;
        });
        return ResultUtils.success(userPage);
    }

    /**
     * 管理员：查看任意指定用户的完整信息
     * 仅管理员可访问 —— @SaCheckRole("admin")（普通用户看自己请用 /user/userInfo）
     */
    @SaCheckRole("admin")
    @GetMapping("/getUserInfo")
    public BaseResponse<User> getUserInfo( @RequestParam  Long id) {
        ThrowUtils.throwIf(id==null,ErrorCode.PARAMS_ERROR,"传入的用户id不能为空");
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return ResultUtils.success(user);
    }

    /**
     * 用户编辑信息
     * @param userEditDTO
     * @return
     */
    @SaCheckLogin
    @PostMapping("/edit")
    public BaseResponse<String> editUserInfo(@RequestBody UserEditDTO userEditDTO){
        userService.editUserInfo(userEditDTO);
        return ResultUtils.success("更新成功");
    }


    /**
     * 修改密码
     * @param id
     * @param password
     * @return
     */
    @SaCheckLogin
    @PostMapping("/editPassword")
    public BaseResponse<String> editUserPassword(Long id,String password){
        userService.editUserPassword(id,password);
        return ResultUtils.success("修改密码成功");
    }

    /**
     * 忘记密码
     * @param userId
     * @param Password
     * @return
     */
    @PostMapping("/forgetPassword")
    public BaseResponse<Boolean> forgetPassword(Long userId,String Password){
        userService.forgetPassword(userId,Password);
        return ResultUtils.success(true);
    }

}
