package com.lsq.dto.python;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PythonUploadImageResponse {

    private String description;

    private Integer embeddingDim;

    private String imageId;

    private Boolean success;
}
