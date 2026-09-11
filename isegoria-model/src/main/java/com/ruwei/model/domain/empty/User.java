package com.ruwei.domain.empty;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 用户表
 * @TableName user
 */
@TableName(value ="user")
@Data
public class User implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
    /**
     * 
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 对外展示的唯一编码
     */
    private Long userId;

    /**
     * 登录名(手机或邮箱)
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * bcrypt加密
     */
    private String password;

    /**
     * 头像URL
     */
    private String avatar;

    /**
     * 0未知 1男 2女
     */
    private Integer gender;

    /**
     * 
     */
    private Date birthday;

    /**
     * 个性签名
     */
    private String bio;

    /**
     * 所在地
     */
    private String location;

    /**
     * 等级
     */
    private Integer level;

    /**
     * 经验值
     */
    private Integer exp;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 1正常 2禁用 3注销
     */
    private Integer status;

    /**
     * 关注数
     */
    private Integer followCount;

    /**
     * 粉丝数
     */
    private Integer fansCount;

    /**
     * 发帖数
     */
    private Integer postCount;

    /**
     * 创建日期
     */
    private Date createdAt;

    /**
     * 修改日期
     */
    private Date updatedAt;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

    /**
     * 是否管理员：1-是，0-否
     */
    private Integer admin;
}