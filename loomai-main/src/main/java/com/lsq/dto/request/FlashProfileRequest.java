package com.lsq.dto.request;

import lombok.Data;

// Flash 用户画像设置请求体
@Data
public class FlashProfileRequest {

    private String style;

    private String color;

    private String bodyType;
}
