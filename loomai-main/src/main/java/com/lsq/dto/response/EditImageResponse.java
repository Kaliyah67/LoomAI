package com.lsq.dto.response;

import lombok.Data;

@Data
public class EditImageResponse {

    // 经过python服务返回的url地址
    private String url;

    // 需要保存的oss地址
    private String saveUrl;
}
