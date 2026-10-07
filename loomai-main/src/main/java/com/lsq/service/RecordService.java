package com.lsq.service;

import com.lsq.entity.Record;
import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.response.EditImageResponse;
import com.lsq.dto.response.MergeImageResponse;

import java.util.List;

// 历史记录服务接口
public interface RecordService {
    // 查询当前用户的调用记录
    List<Record> my(Long userId, String action);

    // 编辑生成调用记录
    void editSave (Long userId, EditImageRequest editImageRequest, EditImageResponse editImageResponse);

    // 合并生成的调用记录
    void mergeSave (Long userId, MergeImageRequest mergeImageRequest, MergeImageResponse mergeImageResponse);
}

