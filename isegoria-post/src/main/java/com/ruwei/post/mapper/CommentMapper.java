package com.ruwei.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruwei.model.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

/**
* @author Administrator
* @description 针对表【comment(评论表(两级盖楼))】的数据库操作Mapper
* @createDate 2026-08-14 16:44:41
* @Entity com.ruwei.model.entity.Comment
*/
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

}




