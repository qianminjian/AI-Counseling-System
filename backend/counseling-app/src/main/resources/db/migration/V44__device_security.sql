-- V44: 设备安全密钥列（P0-1 设备签名鉴权基础设施，doing/84 §六.2 security follow-up）
--
-- 背景：审计发现 /api/v1/device/report/** 无签名鉴权（P0-1，2026-08-11），
-- 报告/心跳/状态/声纹端点为 permitAll，任意知道 deviceCode 者可伪造设备上报。
-- 本次：device 表加 device_secret（reportOnline 时生成并返回，设备存储后后续请求
-- 用 HMAC-SHA256 签名）；reportOnline 公开（首次上线必须），其余 report/* 收至
-- deviceToken 鉴权（SecurityConfig 白名单同步收紧）。
-- 固件侧签名实现待 NST-HW-02 二期（frozen/73 §九 追认）。
-- schema：挂 tenant_template，无 tenant_id 列（平台级，TenantLineHandler 忽略名单同步追加）。

ALTER TABLE tenant_template.device
    ADD COLUMN IF NOT EXISTS device_secret VARCHAR(64);  -- HMAC 密钥（reportOnline 生成，SHA-256 hex 32 字节）

COMMENT ON COLUMN tenant_template.device.device_secret IS '设备 HMAC 密钥（P0-1：reportOnline 生成后返回，设备存储；请求签名 = HMAC-SHA256(body+timestamp+nonce, secret)——固件侧对接后启用；当前仅存储待固件二期）';

ALTER TABLE tenant_template.device
    ADD COLUMN IF NOT EXISTS device_token VARCHAR(256);  -- JWT 设备身份令牌（reportOnline 签发，供后续 report/* 鉴权）

COMMENT ON COLUMN tenant_template.device.device_token IS '设备 JWT token（P0-1：reportOnline 成功后签发，有效期 24h，subject=deviceCode；设备用于后续 report/heartbeat/status/voiceprint 鉴权；SecurityConfig /report/* 白名单同步收紧）';