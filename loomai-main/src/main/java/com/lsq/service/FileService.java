package com.lsq.service;

import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.request.SearchImageRequest;
import com.lsq.dto.response.*;
import com.lsq.entity.FileInfo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FileService {

    FileUploadResponse uploadImage(MultipartFile file, String authorization);

    // 查询当前用户上传的图片列表
    List<FileInfo> myImages(String authorization);

    // 搜索当前用户上传的图片
    List<SearchImageResponse> search(String authorization, SearchImageRequest searchImageRequest);

    // 根据指令编辑用户上传的图片（同步）
    EditImageResponse edit(String authorization, EditImageRequest editImageRequest);

    // 合并用户上传的图片（同步）
    MergeImageResponse merge(String authorization, MergeImageRequest mergeImageRequest);

    // 异步提交图片编辑任务
    TaskSubmitResponse submitEditAsync(String authorization, EditImageRequest request);

    // 异步提交图片合并任务
    TaskSubmitResponse submitMergeAsync(String authorization, MergeImageRequest request);

    // 查询异步任务状态
    TaskStatusResponse getTaskStatus(String taskId);

    // 查询当前用户的异步任务列表（含处理中/成功/失败，action 可选过滤 edit/merge）
    List<TaskStatusResponse> listMyTasks(String authorization, String action);
}
