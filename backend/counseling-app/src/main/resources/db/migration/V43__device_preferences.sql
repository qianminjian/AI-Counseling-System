-- V43: 设备偏好表（TOC-006 远程管理软件侧，doing/85 §四）
--
-- 背景：toC 家庭远程管理——家长设置设备偏好（音量/音色/对话偏好），
-- 设备端拉取配置（/device/config/pull）时下发；真实固件执行属 NST-HW-02 二期。
-- 表挂 device 域（pullConfig 同域直查）+ family_account_id 归属（toC 数据隔离）。
-- schema 约定：平台级表（无 tenant_id 列，TenantLineHandler 忽略名单同步追加）。

CREATE TABLE IF NOT EXISTS tenant_template.device_preferences (
    pref_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_code      VARCHAR(16)  NOT NULL,           -- 设备短码（机身二维码载体）
    family_account_id UUID        NOT NULL,           -- 归属家庭账号（toC 数据隔离键）
    volume           INT,                             -- 音量 0-100（NULL=未设置，设备默认）
    voice_persona    VARCHAR(32),                     -- 音色（VoicePersonaResolver 对齐）
    dialogue_pref    VARCHAR(64),                     -- 对话偏好（如 gentle/energetic）
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_device_prefs_family UNIQUE (device_code, family_account_id)
);

COMMENT ON TABLE  tenant_template.device_preferences IS '设备偏好（TOC-006 远程管理软件侧；平台级表，按 family_account_id 隔离）';
COMMENT ON COLUMN tenant_template.device_preferences.volume IS '音量 0-100；固件执行待 NST-HW-02 二期';
