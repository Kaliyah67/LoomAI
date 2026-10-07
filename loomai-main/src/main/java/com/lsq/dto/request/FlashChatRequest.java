package com.lsq.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// Flash 聊天请求体
@Data
public class FlashChatRequest {

    // 用户输入内容
    @NotBlank(message = "消息不能为空")
    private String message;

    // 会话ID（前端生成，用于短期记忆；可空）
    private String sessionId;
}
