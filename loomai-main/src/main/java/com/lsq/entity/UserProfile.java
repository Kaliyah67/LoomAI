package com.lsq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

// Flash 用户长期画像表实体类
@Data
@TableName("user_profile")
public class  UserProfile implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID（唯一）
    private Long userId;

    // 风格偏好
    private String style;

    // 颜色偏好
    private String color;

    // 体型
    private String bodyType;

    // 更新时间
    private LocalDateTime updatedAt;
}
