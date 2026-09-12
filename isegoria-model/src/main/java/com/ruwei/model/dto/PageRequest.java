package com.ruwei.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页查询基类（全量分页 DTO 的公共父类）。
 *
 * <p>所有分页查询 DTO 都应继承本类，统一携带分页参数（{@code current} / {@code pageSize}）
 * 与可选排序参数（{@code sortField} / {@code sortOrder}），避免各 DTO 重复声明。
 * 排序方向约定与 {@code QueryWrapperUtils} 一致：{@code ascend} = 升序，其它或空 = 降序。</p>
 *
 * <p><b>归属说明</b>：本类原在旧单体 {@code com.ruwei.common.PageRequest}。微服务拆分时下沉到
 * model 模块（而非留在 common），因为它是纯数据契约、且被 model 内 9 个分页 DTO 继承——
 * 这样 model 无需依赖 common（common 携带 web/Mybatis-Plus/Sa-Token 等 starter），
 * 依赖方向保持 {@code common → model} 单向，Dubbo 契约模块得以保持纯 POJO。</p>
 *
 * @author ruwei
 */
@Data
public class PageRequest implements Serializable {

    @Serial
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
