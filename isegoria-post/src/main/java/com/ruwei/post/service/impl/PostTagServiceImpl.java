package com.ruwei.post.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.model.entity.PostTag;
import com.ruwei.post.mapper.PostTagMapper;
import com.ruwei.post.service.PostTagService;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【post_tag(帖子标签关联表)】的数据库操作Service实现
* @createDate 2026-08-05 10:25:17
*/
@Service
public class PostTagServiceImpl extends ServiceImpl<PostTagMapper, PostTag>
    implements PostTagService{

}




