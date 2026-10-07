package com.lsq.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsq.config.RabbitMqConfig;
import com.lsq.dto.TaskMessage;
import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.response.EditImageResponse;
import com.lsq.dto.response.MergeImageResponse;
import com.lsq.dto.response.TaskStatusResponse;
import com.lsq.service.PythonAiIntegrationService;
import com.lsq.service.RecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaskMessageConsumer {

    private static final String TASK_KEY_PREFIX = "task:";
    private static final Duration TASK_TTL = Duration.ofHours(1);

    private final PythonAiIntegrationService pythonAiIntegrationService;
    private final RecordService recordService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // 监听消息队列
    @RabbitListener(queues = RabbitMqConfig.TASK_QUEUE)
    public void handleTask(TaskMessage message) {
        String taskId = message.getTaskId();
        log.info("收到任务消息, taskId={}, type={}", taskId, message.getType());

        try {
            // 更新 redis 数据库状态
            updateTaskStatus(taskId, "PROCESSING", null, null, null);

            if ("EDIT".equals(message.getType())) {
                handleEditTask(message);
            } else if ("MERGE".equals(message.getType())) {
                handleMergeTask(message);
            } else {
                updateTaskStatus(taskId, "FAILED", null, null, "未知任务类型: " + message.getType());
            }

            log.info("任务处理完成, taskId={}", taskId);
        } catch (Exception e) {
            log.error("任务处理失败, taskId={}", taskId, e);
            updateTaskStatus(taskId, "FAILED", null, null, e.getMessage());
        }
    }

    private void handleEditTask(TaskMessage message) {
        Map<String, String> params = message.getParams();
        EditImageRequest request = new EditImageRequest();
        request.setImage(params.get("image"));
        request.setInstruction(params.get("instruction"));

        EditImageResponse response = pythonAiIntegrationService.edit(request);

        // 更新Redis状态
        updateTaskStatus(message.getTaskId(), "SUCCESS", response.getUrl(), response.getSaveUrl(), null);

        recordService.editSave(message.getUserId(), request, response);
    }

    private void handleMergeTask(TaskMessage message) {
        Map<String, String> params = message.getParams();
        MergeImageRequest request = new MergeImageRequest();
        request.setImage1(params.get("image1"));
        request.setImage2(params.get("image2"));
        request.setInstruction(params.get("instruction"));

        MergeImageResponse response = pythonAiIntegrationService.merge(request);

        updateTaskStatus(message.getTaskId(), "SUCCESS", response.getUrl(), response.getSaveUrl(), null);

        recordService.mergeSave(message.getUserId(), request, response);
    }

    // 更新任务状态：只改状态相关字段，保留提交时写入的 type/instruction/inputImage/createdAt 等输入信息
    private void updateTaskStatus(String taskId, String status, String resultUrl, String saveUrl, String errorMessage) {
        TaskStatusResponse current = readTaskStatus(taskId);
        if (current == null) {
            current = new TaskStatusResponse();
            current.setTaskId(taskId);
        }
        current.setStatus(status);
        current.setResultUrl(resultUrl);
        current.setSaveUrl(saveUrl);
        current.setErrorMessage(errorMessage);
        writeTaskStatus(current);
    }

    private TaskStatusResponse readTaskStatus(String taskId) {
        String json = stringRedisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TaskStatusResponse.class);
        } catch (Exception e) {
            log.warn("读取任务状态失败, taskId={}", taskId, e);
            return null;
        }
    }

    private void writeTaskStatus(TaskStatusResponse status) {
        try {
            String json = objectMapper.writeValueAsString(status);
            stringRedisTemplate.opsForValue().set(TASK_KEY_PREFIX + status.getTaskId(), json, TASK_TTL);
        } catch (JsonProcessingException e) {
            log.error("序列化任务状态失败, taskId={}", status.getTaskId(), e);
        }
    }
}
