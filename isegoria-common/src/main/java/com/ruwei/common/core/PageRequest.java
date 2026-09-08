package com.ruwei.common.core;

import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询基类。
 *
 * <p>所有分页查询 DTO 都应继承本类，统一携带分页参数（{@code current} / {@code pageSize}）
 * 与可选排序参数（{@code sortField} / {@code sortOrder}），避免各 DTO 重复声明。
 * 排序方向约定与 {@code QueryWrapperUtils} 一致：{@code ascend} = 升序，其它或空 = 降序。</p>
 */
@Data
public class PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页码，从 1 开始，默认 1
     */
    private long current = 1;

    /**
     * 每页条数，默认 10
     */
    private long pageSize = 10;

    /**
     * 排序字段（对应数据库驼峰列名，为空则不排序）
     */
    private String sortField;

    /**
     * 排序方向：{@code ascend} = 升序，其它或空 = 降序
     */
    private String sortOrder = "descend";
}
