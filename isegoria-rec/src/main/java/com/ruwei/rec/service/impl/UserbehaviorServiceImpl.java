package com.ruwei.rec.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;

import com.ruwei.model.entity.userBehavior;
import com.ruwei.rec.mapper.userbehaviorMapper;
import com.ruwei.rec.service.UserbehaviorService;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【userbehavior(用户行为日志)】的数据库操作Service实现
* @createDate 2026-08-26 09:41:52
*/
@Service
public class UserbehaviorServiceImpl extends ServiceImpl<userbehaviorMapper, userBehavior>
    implements UserbehaviorService {

}




