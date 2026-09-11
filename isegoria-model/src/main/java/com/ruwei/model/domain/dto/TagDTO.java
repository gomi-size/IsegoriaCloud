package com.ruwei.domain.dto;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;

/**
 * 标签请求类
 */

@Data
public class TagDTO implements Serializable {

    /**
     * 标签名(唯一)
     */
    private String name;



    private static final long serialVersionUID = 1L;
}