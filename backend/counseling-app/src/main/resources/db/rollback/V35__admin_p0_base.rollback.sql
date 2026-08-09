-- V35 rollback: 后台管理端 P0 底座表（ADMIN-P0-01）
-- 回滚：删除两张平台级表（管理端 P0 停用时整体回退）

DROP TABLE IF EXISTS tenant_template.service_health_snapshots;
DROP TABLE IF EXISTS tenant_template.platform_admin;
