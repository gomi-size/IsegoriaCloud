package com.ruwei.domain.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UserEditDTO {
    private Long id; //用于增删改查(只允许管理员看到)

    private String nickname;    // 昵称

    private String avatar;      // 头像URL

    private Integer gender;     // 0未知 1男 2女

    private LocalDate birthday; // 生日

    private String bio;         // 个性签名

    private String location;    // 所在地

    private String phone;       //手机号

    private String email;       //邮件

}
