package com.ruwei.rec.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;

import com.ruwei.model.entity.UserInterest;
import com.ruwei.rec.mapper.UserInterestMapper;
import com.ruwei.rec.service.UserInterestService;
import org.springframework.stereotype.Service;

/**
 * 针对表【user_interest(用户长期兴趣画像)】的数据库操作Service实现
 *
 * <p>注意：MP 3.5.9+ ServiceImpl 在 {@code com.baomidou.mybatisplus.spring.service.impl} 新包名
 * （与 userBehaviorServiceImpl 等现有实现保持一致，勿用旧包 {@code extension.service.impl}）。</p>
 */
@Service
public class UserInterestServiceImpl extends ServiceImpl<UserInterestMapper, UserInterest> implements UserInterestService {
}