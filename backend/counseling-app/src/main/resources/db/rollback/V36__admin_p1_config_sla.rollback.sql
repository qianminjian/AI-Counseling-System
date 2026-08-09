-- V36 rollback: 后台管理端 P1 表（ADMIN-P1-01/02/05）
-- 回滚：删除三张新表 + 撤销 prompt_versions.status 列（数据回填不可逆，仅列删除）

ALTER TABLE tenant_template.prompt_versions DROP COLUMN IF EXISTS status;

DROP TABLE IF EXISTS tenant_template.sla_escalation_log;
DROP TABLE IF EXISTS tenant_template.sys_config_history;
DROP TABLE IF EXISTS tenant_template.sys_config;
