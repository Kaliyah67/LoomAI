package com.lsq.service;

import com.lsq.entity.UserProfile;

// Flash 用户长期画像服务接口
public interface UserProfileService {

    // 根据用户ID查询画像，不存在返回 null
    UserProfile getByUserId(Long userId);

    // 保存或更新用户画像
    void saveOrUpdate(Long userId, String style, String color, String bodyType);
}
