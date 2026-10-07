package com.lsq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "python.ai")
public class PythonAiServiceProperties {

    /**
     * Python 服务根地址，例如 http://127.0.0.1:5000
     */
    private String baseUrl = "http://127.0.0.1:5000";

    /**
     * 是否在上传完成后调用 Python 接口；关闭时跳过 HTTP 调用。
     */
    private boolean enabled = true;
}
