package com.ruwei.interaction.service.Impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.interaction.mapper.PostLikeMapper;
import com.ruwei.interaction.service.PostLikeService;
import com.ruwei.model.entity.PostLike;
import org.springframework.stereotype.Service;

/**
 * 帖子点赞表 {@code post_like} 的数据库操作 Service 实现。
 *
 * <p>纯 MyBatis-Plus 通用 CRUD 透传，无自定义逻辑：点赞关系的真实读写发生在
 * {@code LikeServiceImpl}（Redis 先行 + MQ 落库）与 {@code LikePersistConsumer}（幂等落库）
 * 两处，本类仅提供 {@code IService} 能力供其复用。</p>
 *
 * @author ruwei
 */
@Service
public class PostLikeServiceImpl extends ServiceImpl<PostLikeMapper, PostLike>
    implements PostLikeService {

}
