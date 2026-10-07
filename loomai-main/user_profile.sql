-- Flash 智能助手：用户长期画像表（风格/颜色/体型偏好）
-- 独立于 init.sql，可单独执行，不会影响现有表。
CREATE TABLE IF NOT EXISTS `user_profile` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，自增',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `style` VARCHAR(255) DEFAULT NULL COMMENT '风格偏好，如：简约/复古/街头',
    `color` VARCHAR(255) DEFAULT NULL COMMENT '颜色偏好，如：黑白灰/暖色系',
    `body_type` VARCHAR(255) DEFAULT NULL COMMENT '体型，如：偏瘦/微胖/梨形',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户穿搭画像表';
