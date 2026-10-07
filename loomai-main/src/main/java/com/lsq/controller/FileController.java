package com.lsq.controller;

import com.lsq.common.Result;
import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.request.SearchImageRequest;
import com.lsq.dto.response.EditImageResponse;
import com.lsq.dto.response.FileUploadResponse;
import com.lsq.dto.response.MergeImageResponse;
import com.lsq.dto.response.SearchImageResponse;
import com.lsq.entity.FileInfo;
import com.lsq.log.ApiLog;
import com.lsq.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileService fileService;

    @ApiLog
    @PostMapping("/upload/image")
    public Result<FileUploadResponse> uploadImage(@RequestParam("file") MultipartFile file,
                                                  @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("图片上传成功", fileService.uploadImage(file, authorization));
    }

    // 我的图片列表（用来展示当前用户上传的图片）
    @ApiLog
    @GetMapping("/my-images")
    public Result<List<FileInfo>> myImages(
            @RequestHeader(value = "Authorization") String authorization
    ) {
        List<FileInfo> imageFiles = fileService.myImages(authorization);
        return Result.success("查询成功", imageFiles);
    }

    // 图片搜索（支持 文搜图/图搜图 两种方式）
    @ApiLog
    @PostMapping("/search")
    public Result<List<SearchImageResponse>> search(@RequestHeader(value = "Authorization") String authorization,
                                                    @RequestParam(value = "query", required = false) String query,
                                                    @RequestParam(value = "file", required = false) MultipartFile file) {
        SearchImageRequest searchImageRequest = new SearchImageRequest();
        searchImageRequest.setQuery(query);
        searchImageRequest.setFile(file);
        return Result.success("查询成功", fileService.search(authorization, searchImageRequest));
    }

    // 图片编辑
    @ApiLog
    @PostMapping("/edit")
    public Result<EditImageResponse> edit(@RequestBody @Validated EditImageRequest editImageRequest,
                                          @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("编辑成功", fileService.edit(authorization, editImageRequest));
    }

    // 图片合并
    @ApiLog
    @PostMapping("/merge")
    public Result<MergeImageResponse> merge(@RequestBody @Validated MergeImageRequest mergeImageRequest,
                                            @RequestHeader(value = "Authorization") String authorization) {
        return Result.success("合并成功", fileService.merge(authorization, mergeImageRequest));
    }





}



















