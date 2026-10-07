package com.lsq.controller;

import com.lsq.common.Result;
import com.lsq.dto.request.FlashChatRequest;
import com.lsq.dto.request.FlashProfileRequest;
import com.lsq.entity.UserProfile;
import com.lsq.log.ApiLog;
import com.lsq.service.FlashService;
import com.lsq.service.UserProfileService;
import com.lsq.utill.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

// Flash 实时对话控制器（SSE）
@RestController
@Slf4j
@RequestMapping("/api/flash")
public class FlashController {

    @Autowired
    private FlashService flashService;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private JwtUtil jwtUtil;

    // 实时对话（SSE 流式输出）
    @PostMapping("/chat")
    public SseEmitter chat(@RequestHeader("Authorization") String authorization,
                           @RequestBody @Validated FlashChatRequest request) {
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        SseEmitter emitter = new SseEmitter(300_000L);
        // 异步处理，避免阻塞 Tomcat 线程
        CompletableFuture.runAsync(() ->
                flashService.streamChat(emitter, userId, request.getSessionId(), request.getMessage()));
        return emitter;
    }

    // 查询当前用户画像
    @ApiLog
    @GetMapping("/profile")
    public Result<UserProfile> getProfile(@RequestHeader("Authorization") String authorization) {
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        return Result.success("查询成功", userProfileService.getByUserId(userId));
    }

    // 保存当前用户画像
    @ApiLog
    @PostMapping("/profile")
    public Result<String> saveProfile(@RequestHeader("Authorization") String authorization,
                                      @RequestBody FlashProfileRequest request) {
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        userProfileService.saveOrUpdate(userId, request.getStyle(), request.getColor(), request.getBodyType());
        return Result.success("保存成功", "保存成功");
    }
}
