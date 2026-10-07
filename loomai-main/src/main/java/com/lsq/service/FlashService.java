package com.lsq.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Flash 实时对话服务接口
public interface FlashService {

    // 转发到 Python /api/chat，并把流式结果透传给前端
    void streamChat(SseEmitter emitter, Long userId, String sessionId, String message);
}
