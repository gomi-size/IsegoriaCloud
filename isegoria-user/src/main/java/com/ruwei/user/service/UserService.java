package com.ruwei.user.service;


import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.dto.UserEditDTO;
import com.ruwei.model.dto.UserLoginDTO;
import com.ruwei.model.dto.UserRegisterDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.vo.UserVO;

/**
* @author chenhang
* @description 针对表【user(用户表)】的数据库操作Service
* @createDate 2026-07-22 14:07:39
*/
public interface UserService extends IService<User> {

    /**
     * 用户注册
     * @param userRegisterDTO
     * @return
     */
    User userRegister(UserRegisterDTO userRegisterDTO);

    /**
     * 用户登录
     * @param userLogin
     * @return
     */
    User userLogin(UserLoginDTO userLogin);


    /**
     * 判断是否为管理员
     * @return
     */
    Boolean isAdmin(Long loginId);

    /**
     * 管理员修改用户状态
     * @param userId 目标用户 id
     * @param status 目标状态（StatusEnum.code）
     * @return 是否修改成功
     */
    boolean updateUserStatus(Long userId, Integer status);

    /**
     * 用户编辑
     * @param userEditDTO
     */
    void editUserInfo(UserEditDTO userEditDTO);

    /**
     * 用户修改密码
     * @param id
     * @param password
     */
    void editUserPassword(Long id, String password);

    /**
     * 忘记密码
     * @param userId
     */
    void forgetPassword(Long userId,String Password);

    /**
     * 当前登录用户获取别人的详情（按对外编码 userId 查找，供前端直接调用于查看他人主页）
     * @param userId 对方的对外编码
     */
    UserVO getOtherUserVOInfo(Long userId);

    /**
     * 当前登录用户获取别人的详情（按内部主键 id 查找）。
     * 关注列表/粉丝列表内部统一以内部 id 索引，渲染 VO 时使用本方法。
     * @param id 对方的内部主键
     */
    UserVO getOtherUserVOInfoById(Long id);
}
