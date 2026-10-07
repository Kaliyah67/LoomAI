package com.lsq.controller;

import com.lsq.common.Result;
import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.response.TaskStatusResponse;
import com.lsq.dto.response.TaskSubmitResponse;
import com.lsq.log.ApiLog;
import com.lsq.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/task")
public class TaskController {

    @Autowired
    private FileService fileService;

    @ApiLog
    @PostMapping("/submit-edit")
    public Result<TaskSubmitResponse> submitEdit(
            @RequestBody @Validated EditImageRequest request,
            @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("任务已提交", fileService.submitEditAsync(authorization, request));
    }

    @ApiLog
    @PostMapping("/submit-merge")
    public Result<TaskSubmitResponse> submitMerge(
            @RequestBody @Validated MergeImageRequest request,
            @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("任务已提交", fileService.submitMergeAsync(authorization, request));
    }

    @GetMapping("/status/{taskId}")
    public Result<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        return Result.success("查询成功", fileService.getTaskStatus(taskId));
    }

    @ApiLog
    @GetMapping("/my")
    public Result<List<TaskStatusResponse>> listMyTasks(
            @RequestHeader(value = "Authorization") String authorization,
            @RequestParam(value = "action", required = false) String action) {
        return Result.success("查询成功", fileService.listMyTasks(authorization, action));
    }
}
