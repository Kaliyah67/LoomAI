package com.lsq.service;

import com.lsq.config.RabbitMqConfig;
import com.lsq.dto.TaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendEditTask(TaskMessage message) {
        log.info("发送编辑任务消息, taskId={}", message.getTaskId());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TASK_EXCHANGE,
                RabbitMqConfig.ROUTING_KEY_EDIT,
                message
        );
    }

    public void sendMergeTask(TaskMessage message) {
        log.info("发送合并任务消息, taskId={}", message.getTaskId());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TASK_EXCHANGE,
                RabbitMqConfig.ROUTING_KEY_MERGE,
                message
        );
    }
}
