package com.lsq.dto.response;

import lombok.Data;

@Data
public class TaskStatusResponse {
    private String taskId;
    private String status;       // PENDING / PROCESSING / SUCCESS / FAILED
    private String type;         // EDIT / MERGE
    private String instruction;  // 编辑/合并指令
    private String inputImage;   // 编辑单图 / 合并图1
    private String inputImage2;  // 合并图2（编辑为 null）
    private String resultUrl;
    private String saveUrl;
    private String errorMessage;
    private Long createdAt;      // 提交时间戳（毫秒）
}
