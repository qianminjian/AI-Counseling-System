-- V45: 设备操作审计表（P1 审计落库，doing/84 §六.2）
--
-- 背景：审计发现 batchOperation/ota/reboot/factory-reset 无审计落库（P1-1），
-- 管理员误以为操作已执行。本表记录每次操作受理，保存接收回执。
-- schema：tenant_template（平台级，无 tenant_id 列，TenantLineHandler 忽略名单同步）。

CREATE TABLE IF NOT EXISTS tenant_template.device_operations (
    operation_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_code      VARCHAR(16)  NOT NULL,           -- 目标设备短码
    action           VARCHAR(32)  NOT NULL,           -- ota/reboot/factory-reset/batch-ota/batch-reboot/batch-factory-reset
    operator         VARCHAR(128),                    -- 操作人（平台 admin 用户名 / 设备 channel）
    accepted_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    note             TEXT                             -- 备注（关联 audit_id / 受理说明）
);

CREATE INDEX IF NOT EXISTS idx_device_ops_device ON tenant_template.device_operations(device_code);
CREATE INDEX IF NOT EXISTS idx_device_ops_action ON tenant_template.device_operations(action);

COMMENT ON TABLE  tenant_template.device_operations IS '设备操作审计（P1：batch/ota/reboot/factory-reset 受理留痕，生产必读）';
COMMENT ON COLUMN tenant_template.device_operations.operator IS '操作人（平台 admin 用户名；设备通道为 deviceCode）';