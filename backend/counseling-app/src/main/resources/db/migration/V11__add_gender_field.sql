-- V11: 性别个性化支持
-- 用户表增加 gender 字段（male/female），用于 Prompt 风格、TTS 音色、界面主题差异化

ALTER TABLE tenant_template.users ADD COLUMN IF NOT EXISTS gender VARCHAR(10);

COMMENT ON COLUMN tenant_template.users.gender IS '性别：male/female，用于对话风格与 TTS 音色个性化';
