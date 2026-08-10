-- V39: 无屏终端设备管理表（CFG-001，doing/84 无屏交互终端配置体系 §六.1）
--
-- 背景：toB 第四端（无屏交互终端）设备档案与绑定体系——扫码配网→回连注册→
-- 验证码绑定→日常管理的全生命周期数据底座。设计文档单一事实源：
--   design/doing/84_无屏交互终端配置体系_方案与SPEC.md §六.1（表结构）；
--   doing/74 §8.4（管理台适配 5 能力）。
-- schema 约定：与既有平台表一致挂 tenant_template（无 tenant_id 列，平台级设备
-- 档案；TenantLineHandler 忽略名单同步追加，见 MindSafeTenantLineHandler）。

-- ===== device（设备档案，§6.1） =====
CREATE TABLE IF NOT EXISTS tenant_template.device (
    device_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_code      VARCHAR(16)  NOT NULL,           -- 短码（机身/包装二维码载体，SN 派生 + Luhn 校验位）
    sn               VARCHAR(32)  NOT NULL,           -- 出厂序列号（不对外，仅内部）
    device_type      VARCHAR(32)  NOT NULL DEFAULT 'desk_toy',  -- plush/desk_toy/pendant 等形态描述符
    firmware_version VARCHAR(16),                     -- 当前固件版本（状态上报更新）
    status           VARCHAR(16)  NOT NULL DEFAULT 'UNACTIVATED',  -- UNACTIVATED/PROVISIONING/ONLINE_UNBOUND/ONLINE_BOUND/OFFLINE/RETIRED
    server_url       VARCHAR(128),                    -- 配网写入的服务器地址（toB 校内地址预置可改）
    last_online_at   TIMESTAMPTZ,                     -- 最近在线时间（心跳更新）
    last_offline_at  TIMESTAMPTZ,                     -- 最近离线时间
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_device_code UNIQUE (device_code),
    CONSTRAINT uk_device_sn    UNIQUE (sn)
);

COMMENT ON TABLE  tenant_template.device IS '无屏终端设备档案（CFG-001；平台级表，无 tenant_id 列）';
COMMENT ON COLUMN tenant_template.device.device_code      IS '短码 11 位：10 位 base32（SN 派生）+ 1 位 Luhn 校验位（doing/84 §5.2.1）';
COMMENT ON COLUMN tenant_template.device.status           IS 'UNACTIVATED=未激活 / PROVISIONING=配网中 / ONLINE_UNBOUND=已联网待绑定 / ONLINE_BOUND=已绑定运行中 / OFFLINE=离线 / RETIRED=已注销';

-- ===== device_bindings（绑定关系，§6.1） =====
CREATE TABLE IF NOT EXISTS tenant_template.device_bindings (
    binding_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id      UUID         NOT NULL,             -- device.device_id
    bind_type      VARCHAR(16)  NOT NULL,             -- SCHOOL/CLASS/ROOM（toB）/ FAMILY（toC 预留）
    bind_target_id UUID         NOT NULL,             -- 学校/班级/咨询室/家庭 ID（多租户按现有 tenant 体系）
    student_id     UUID,                              -- toC 单孩绑定；toB 多人共用为 NULL
    bound_by       VARCHAR(64),                       -- 操作人（老师/家长用户 ID）
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/UNBOUND
    bound_at       TIMESTAMPTZ,
    unbound_at     TIMESTAMPTZ,
    CONSTRAINT fk_device_bindings_device FOREIGN KEY (device_id) REFERENCES tenant_template.device (device_id)
);

COMMENT ON TABLE  tenant_template.device_bindings IS '设备绑定关系（CFG-004；toB 学校/班级/咨询室三层归属）';
COMMENT ON COLUMN tenant_template.device_bindings.bind_type IS 'SCHOOL=学校 / CLASS=班级 / ROOM=咨询室 / FAMILY=家庭（toC 预留）';
COMMENT ON COLUMN tenant_template.device_bindings.student_id IS 'toC 单孩绑定；toB 多人共用为 NULL';

-- 绑定查询索引（设备列表按绑定态筛选 + 归属反查）
CREATE INDEX IF NOT EXISTS idx_device_bindings_device
    ON tenant_template.device_bindings (device_id, status);
CREATE INDEX IF NOT EXISTS idx_device_bindings_target
    ON tenant_template.device_bindings (bind_type, bind_target_id, status);

-- ===== device_bind_codes（绑定验证码会话，§6.1） =====
CREATE TABLE IF NOT EXISTS tenant_template.device_bind_codes (
    code_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id    UUID         NOT NULL,
    code_hash    VARCHAR(64)  NOT NULL,               -- 验证码 SHA-256 哈希（不存明文、不落日志）
    expires_at   TIMESTAMPTZ  NOT NULL,               -- 5 分钟有效
    fail_count   INT          NOT NULL DEFAULT 0,     -- 连续失败次数（3 次锁定）
    locked_until TIMESTAMPTZ,                         -- 锁定时长 5 分钟
    used_at      TIMESTAMPTZ,                         -- 一次性：绑定成功即作废
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_device_codes_device FOREIGN KEY (device_id) REFERENCES tenant_template.device (device_id)
);

COMMENT ON TABLE  tenant_template.device_bind_codes IS '设备绑定验证码会话（CFG-004；哈希存储、5 分钟有效、3 次锁定、一次性）';
COMMENT ON COLUMN tenant_template.device_bind_codes.code_hash    IS '验证码 SHA-256 哈希，明文不落库不落日志';
COMMENT ON COLUMN tenant_template.device_bind_codes.fail_count   IS '连续错误次数，达 3 次置 locked_until';
COMMENT ON COLUMN tenant_template.device_bind_codes.locked_until IS '锁定截止时间（5 分钟）';

CREATE INDEX IF NOT EXISTS idx_device_codes_device
    ON tenant_template.device_bind_codes (device_id, used_at);

-- ===== device_qr_issuance（二维码签发记录，§6.1） =====
CREATE TABLE IF NOT EXISTS tenant_template.device_qr_issuance (
    issuance_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id   UUID         NOT NULL,
    issued_by   VARCHAR(64),                          -- 管理员（批量印制签发人）
    qr_payload  VARCHAR(256) NOT NULL,                -- 印刷的 URL 原文（版本留痕）
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_device_qr_device FOREIGN KEY (device_id) REFERENCES tenant_template.device (device_id)
);

COMMENT ON TABLE  tenant_template.device_qr_issuance IS '设备二维码签发记录（CFG-005；批量印制留痕，供回溯）';

CREATE INDEX IF NOT EXISTS idx_device_qr_device
    ON tenant_template.device_qr_issuance (device_id);
