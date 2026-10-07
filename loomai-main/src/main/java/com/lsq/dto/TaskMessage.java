package com.lsq.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskMessage {
    private String taskId;
    private Long userId;
    private String type;   // "EDIT" or "MERGE"
    private Map<String, String> params;
}
