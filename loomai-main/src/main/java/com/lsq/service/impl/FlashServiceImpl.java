package com.lsq.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsq.config.PythonAiServiceProperties;
import com.lsq.entity.UserProfile;
import com.lsq.service.FlashService;
import com.lsq.service.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

// Flash 实时对话服务实现类：转发到 Python /api/chat 并透传 SSE 流
@Service
@Slf4j
public class FlashServiceImpl implements FlashService {

    private static final String CHAT_PATH = "/api/chat";

    private final PythonAiServiceProperties properties;
    private final ObjectMapper objectMapper;
    private final UserProfileService userProfileService;
    private final HttpClient httpClient;

    public FlashServiceImpl(PythonAiServiceProperties properties,
                            ObjectMapper objectMapper,
                            UserProfileService userProfileService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.userProfileService = userProfileService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void streamChat(SseEmitter emitter, Long userId, String sessionId, String message) {
        try {
            // 1. 查长期画像
            UserProfile profile = userProfileService.getByUserId(userId);

            // 2. 组装发给 Python 的请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", userId);
            body.put("sessionId", sessionId);
            body.put("message", message);
            Map<String, Object> profileMap = new LinkedHashMap<>();
            profileMap.put("style", profile == null ? "" : profile.getStyle());
            profileMap.put("color", profile == null ? "" : profile.getColor());
            profileMap.put("bodyType", profile == null ? "" : profile.getBodyType());
            body.put("profile", profileMap);

            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + CHAT_PATH))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            // 3. 读 Python 的 SSE 流并透传给前端
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                emitter.send(Map.of("error", "Python 服务异常（状态码 " + response.statusCode() + "）"));
                emitter.complete();
                return;
            }

            try (Stream<String> lines = response.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line == null || !line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring("data:".length()).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode node = objectMapper.readTree(payload);
                    String delta = node.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        // 用 Map 让 Spring 序列化为 JSON（自动转义换行），前端解析后拼接到回复
                        emitter.send(Map.of("delta", delta));
                    }
                }
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("Flash 对话转发失败", e);
            try {
                emitter.send(Map.of("error", e.getMessage() == null ? "对话失败" : e.getMessage()));
            } catch (Exception ignored) {
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return baseUrl;
        }
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
