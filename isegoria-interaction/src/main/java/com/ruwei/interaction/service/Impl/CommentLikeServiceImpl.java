package com.ruwei.interaction.service.Impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;

import com.ruwei.interaction.mapper.CommentLikeMapper;
import com.ruwei.interaction.service.CommentLikeService;
import com.ruwei.model.entity.CommentLike;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【comment_like(评论点赞表)】的数据库操作Service实现
* @createDate 2026-08-18
*/
@Service
public class CommentLikeServiceImpl extends ServiceImpl<CommentLikeMapper, CommentLike>
    implements CommentLikeService {

}
