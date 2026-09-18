package com.ruwei.rec.mapper;

import com.ruwei.rec.empty.PostDoc;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 帖子 ES 文档的 Spring Data 仓储（PostDoc 索引的读写入口）。
 *
 * <p>注意：本接口<b>不是 MyBatis Mapper</b>，{@code @Mapper} 是从旧单体带过来的历史注解
 * （旧单体 {@code es.mapper} 包不在 {@code @MapperScan} 范围内所以无影响）。
 * 现放在了 {@code com.ruwei.rec.mapper} 包下，会被启动类的 {@code @MapperScan} 扫到，
 * 但 MyBatis 扫描器遇到"已被 Spring Data 注册过的同名单例"会跳过（不报 Bean 冲突）。</p>
 */
@Mapper
public interface PostEsMapper extends ElasticsearchRepository<PostDoc, Long> {
}