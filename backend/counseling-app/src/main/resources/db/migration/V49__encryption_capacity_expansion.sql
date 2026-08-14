-- V49: 字段加密容量扩展（B-05，2026-08-14）
--
-- 背景：ENCRYPTION_ENABLED=true（COMP-008，frozen/60）时 FieldEncryptionService
-- 以 AES-256-GCM + base64 落库（密文格式 v1:<base64>，膨胀约 1.35 倍）：
--   * toc_family_accounts.phone 原 VARCHAR(20)——手机号明文 11 字符 → 密文约 84
--     字符，超长必报错（TocAuthService 已按 encrypt(phone) 读写，启用即爆）；
--   * teacher_notes.content 原 VARCHAR(2048)——中文 2048 字符 ≈ 6144 字节 →
--     密文约 8300 字符，超 VARCHAR 上限。
-- 其余加密字段已满足：message_summaries.content_summary（V32 已扩 TEXT）、
-- counseling_session.session_summary（V9 TEXT）。
-- 本迁移仅放宽列容量，不动存量数据（明文/密文均可容纳）。

ALTER TABLE tenant_template.toc_family_accounts
    ALTER COLUMN phone TYPE VARCHAR(96);

COMMENT ON COLUMN tenant_template.toc_family_accounts.phone
    IS '手机号（登录标识，唯一；B-05 起可能为 AES-GCM 密文，96 容量容纳 v1:+base64）';

ALTER TABLE tenant_template.teacher_notes
    ALTER COLUMN content TYPE TEXT;

COMMENT ON COLUMN tenant_template.teacher_notes.content
    IS '备注内容（B-05 起可能为 AES-GCM 密文；TEXT 容纳密文膨胀）';
