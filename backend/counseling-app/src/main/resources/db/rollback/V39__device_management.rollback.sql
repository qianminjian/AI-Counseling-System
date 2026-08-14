-- V39 rollback: 无屏终端设备管理表（CFG-001，doing/84 §六.1）
-- 回滚：删除设备域四张平台级表（device 主表 + 绑定/验证码/二维码发放子表，
-- 索引与唯一约束随表删除；引用 device 的子表先删）

DROP TABLE IF EXISTS tenant_template.device_qr_issuance;
DROP TABLE IF EXISTS tenant_template.device_bind_codes;
DROP TABLE IF EXISTS tenant_template.device_bindings;
DROP TABLE IF EXISTS tenant_template.device;
