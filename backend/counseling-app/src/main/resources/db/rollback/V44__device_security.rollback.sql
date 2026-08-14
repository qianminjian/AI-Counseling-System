-- V44 rollback: 设备安全密钥列（P0-1 设备签名鉴权基础设施，doing/84 §六.2）
-- 回滚：删除 device_secret 与 device_token 两列（签名鉴权能力回退，
-- 已签发 token 随列删除失效，设备需重新 reportOnline 获取凭证）

ALTER TABLE tenant_template.device DROP COLUMN IF EXISTS device_token;
ALTER TABLE tenant_template.device DROP COLUMN IF EXISTS device_secret;
