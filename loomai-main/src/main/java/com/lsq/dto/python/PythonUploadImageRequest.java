package com.lsq.dto.python;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PythonUploadImageRequest {

    private String ossUrl;

    private Long userId;
}
