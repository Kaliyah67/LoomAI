package com.lsq.service;

import com.lsq.dto.request.AuthRequest;
import com.lsq.dto.request.SendVerificationCodeRequest;
import com.lsq.dto.response.AuthResponse;
import jakarta.validation.Valid;

// 用户模块服务接口
public interface UserService {

    // 发送验证码
    boolean sendVerificationCode(SendVerificationCodeRequest request);


    // 认证注册/登录
    AuthResponse auth(@Valid AuthRequest request);

    // 用户等处系统
    boolean logut(String authorization);
}
