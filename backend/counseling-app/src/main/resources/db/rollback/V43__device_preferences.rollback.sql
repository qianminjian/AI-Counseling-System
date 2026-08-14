-- V43 rollback: 设备偏好表（TOC-006 远程管理软件侧，doing/85 §四）
-- 回滚：删除 device_preferences 表（唯一约束随表删除）

DROP TABLE IF EXISTS tenant_template.device_preferences;
