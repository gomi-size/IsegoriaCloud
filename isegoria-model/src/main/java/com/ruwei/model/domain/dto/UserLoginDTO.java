package com.ruwei.domain.dto;

import lombok.Data;


/**
 * 用户登录
 */
@Data
public class UserLoginDTO {

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

}