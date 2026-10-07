package com.lsq.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lsq.entity.UserProfile;
import com.lsq.mapper.UserProfileMapper;
import com.lsq.service.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Flash 用户长期画像服务实现类
@Service
@Slf4j
public class UserProfileServiceImpl implements UserProfileService {

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Override
    public UserProfile getByUserId(Long userId) {
        LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserProfile::getUserId, userId);
        return userProfileMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrUpdate(Long userId, String style, String color, String bodyType) {
        UserProfile existing = getByUserId(userId);
        if (existing == null) {
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setStyle(style);
            profile.setColor(color);
            profile.setBodyType(bodyType);
            userProfileMapper.insert(profile);
        } else {
            existing.setStyle(style);
            existing.setColor(color);
            existing.setBodyType(bodyType);
            userProfileMapper.updateById(existing);
        }
    }
}
