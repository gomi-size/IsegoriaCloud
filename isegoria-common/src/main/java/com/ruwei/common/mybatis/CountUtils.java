package com.ruwei.common.mybatis;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 通用计数工具：DB 层原子自增/自减（{@code setSql} 拼 SQL，避免并发读改写丢失）。
 *
 * <p>对齐项目「计数用 DB 层 SQL 原子自增减」的约定，供各 Service 复用：
 * user_follow 的 followCount/fansCount、post 的板块 postCount / 作者 postCount、tag 的 useCount 等。
 * 调用方须保证 {@code field} 为数据库真实驼峰列名（走常量，勿拼用户输入）。</p>
 *
 * <p>用法示例：{@code CountUtils.increment(userService, User::getId, loginId, "followCount", 1)}</p>
 */
public final class CountUtils {

    private CountUtils() {
    }

    /**
     * 原子自增/自减指定实体的计数字段。
     *
     * @param service  目标实体对应的 IService（提供 update(Wrapper) 能力）
     * @param idColumn 主键列 lambda，如 {@code User::getId} / {@code Board::getId}
     * @param idValue  主键值（内部 id）
     * @param field    待增减的计数字段名（与数据库列一致，如 followCount / fansCount / postCount）
     * @param delta    增量（正数加、负数减）
     * @param <T>      实体类型
     * @return 是否更新成功（影响行数 &gt; 0）
     */
    public static <T> boolean increment(IService<T> service, SFunction<T, ?> idColumn,
                                        Object idValue, String field, int delta) {
        LambdaUpdateWrapper<T> uw = new LambdaUpdateWrapper<>();
        uw.eq(idColumn, idValue)
          .setSql(field + " = " + field + (delta >= 0 ? " + " : " - ") + Math.abs(delta));
        return service.update(uw);
    }
}
