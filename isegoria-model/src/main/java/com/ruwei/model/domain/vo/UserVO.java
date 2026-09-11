package com.ruwei.domain.vo;

import com.ruwei.domain.Enum.StatusEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserVO {

    private Long id;            //用于增删改查(只允许管理员看到)

    private Long userId;      // 对外展示的唯一编码

    private String username;    // 登录名

    private String nickname;    // 昵称

    private String avatar;      // 头像URL

    private Integer gender;     // 0未知 1男 2女

    private LocalDate birthday; // 生日

    private String bio;         // 个性签名

    private String location;    // 所在地

    private Integer level;      // 等级

    private Integer exp;        // 经验值

    private String phone;       //手机号

    private String email;       //邮件

    private Integer followCount;// 关注数

    private Integer fansCount;  // 粉丝数

    private Integer postCount;  // 发帖数

    private StatusEnum status; //账号状态

    private Integer admin;      // 是否管理员：1-是，0-否

    private Boolean isFollowed;   // 当前登录用户是否关注了他

    private Boolean isMutual;   // 是否互相关注

    private Boolean isFans;     // 是否是粉丝

    private LocalDateTime createdAt; // 注册时间

}