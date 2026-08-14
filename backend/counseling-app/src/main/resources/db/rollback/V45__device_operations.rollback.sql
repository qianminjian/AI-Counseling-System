-- V45 rollback: 设备操作审计表（P1 审计落库，doing/84 §六.2）
-- 回滚：删除 device_operations 表（操作受理留痕回退，索引随表删除）

DROP TABLE IF EXISTS tenant_template.device_operations;
