package com.lsq.dto.response;

import lombok.Data;

@Data
public class FileUploadResponse {

    private String url;

    private String fileName;

    private Long fileSize;
}
