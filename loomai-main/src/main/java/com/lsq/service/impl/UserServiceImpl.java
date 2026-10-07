package com.lsq.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lsq.dto.request.AuthRequest;
import com.lsq.dto.request.SendVerificationCodeRequest;
import com.lsq.dto.response.AuthResponse;
import com.lsq.entity.User;
import com.lsq.mapper.UserMapper;
import com.lsq.service.UserService;
import com.lsq.utill.EmailService;
import com.lsq.utill.JwtUtil;
import com.lsq.utill.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 用户Service实现类
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    // 做密码加密解密、密码校验的对象
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();


    @Override
    public boolean sendVerificationCode(SendVerificationCodeRequest request) {
        String email = request.getEmail();
        // 验证码还没失效呢 拒绝申请
        if (verificationCodeService.hasCode(email)) {
            throw new RuntimeException("验证码尚未过期，请勿重复发送");
        }

        // 生成验证码
        String code = verificationCodeService.generateCode();
        // 储存验证码
        verificationCodeService.saveCode(email, code);
        // 发送验证码
        return emailService.sendVerificationCode(email, code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthResponse auth(AuthRequest request) {

        // 1. 获取用户名
        String account = request.getAccount();

        // 2. 判断当前认证方式：如果用户名中有@就是用邮箱认证，没有就是用户名+密码
        boolean isEmail = account.contains("@");

        // 3. 查询用户是否存在
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, account);
        User user = userMapper.selectOne(queryWrapper);

        // 4. 邮箱+验证码的方式
        if (isEmail) {
            String code = request.getVerificationCode();
            if (code == null || code.isEmpty()) {
                throw new RuntimeException("验证码不能为空");
            }
            // 5. 验证一下验证码
            if (!verificationCodeService.verifyCode(account, code)) {
                throw new RuntimeException("验证码错误 或 已过期");
            }
            // 6. 处理新老用户：
            // 新用户直接注册
            if (user == null) {
                user = new User();
                user.setUsername(account);
                user.setEmail(account);
                userMapper.insert(user);
                log.info("新用户注册成功，邮箱{}", user.getEmail());
            }
        } else {
            // 7. 用户名+密码
            if (user == null) {
                user = new User();
                user.setUsername(account);
                if (request.getPassword() == null || request.getPassword() == "") {
                    throw new RuntimeException("密码不能为空");
                }
                user.setPasswordhash(passwordEncoder.encode(request.getPassword()));
                userMapper.insert(user);
            } else {
                // 8. 校验请求参数中的密码
                if (user.getPasswordhash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordhash())) {
                    throw new RuntimeException("用户名或密码错误");
                }
            }
        }
        return createResponse(user);

    }

    @Override
    public boolean logut(String authorization) {
        String token = jwtUtil.parseToken(authorization);
        return jwtUtil.removeToken(token);
    }

    // 返回认证的响应
    private AuthResponse createResponse(User user) {
        AuthResponse response = new AuthResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setToken(jwtUtil.generateToken(user));
        return response;
    }

}



































