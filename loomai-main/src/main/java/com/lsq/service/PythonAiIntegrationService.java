package com.lsq.service;

import com.lsq.dto.python.PythonUploadImageResponse;
import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.request.SearchImageRequest;
import com.lsq.dto.response.EditImageResponse;
import com.lsq.dto.response.MergeImageResponse;
import com.lsq.dto.response.SearchImageResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 与 Python AI / 向量检索等侧车服务的 HTTP 集成，供多处复用。
 */
public interface PythonAiIntegrationService {

    /**
     * 将已上传到 OSS 的图片登记到 Python 侧，用于生成描述与向量以便后续搜索。
     * 网络或非 2xx 响应时返回 empty，不向外抛异常，以免影响主业务流程。
     */
    Optional<PythonUploadImageResponse> registerUploadedImage(Long userId, String ossUrl);

    // 审核图片：上传的图片只能是 人物 或者 衣服
    boolean validateImage(Path filePath);

    // 搜索图片
    List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest);

    // 编辑图片
    EditImageResponse edit(EditImageRequest editImageRequest);

    // 合并图片
    MergeImageResponse merge(MergeImageRequest mergeImageRequest);
}


















