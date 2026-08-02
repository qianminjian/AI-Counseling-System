-- V10: 用户方言偏好字段（design/56 §三：TTS音色矩阵与方言设计）
-- 多租户模板 schema，新建租户时自动继承
ALTER TABLE tenant_template.users ADD COLUMN IF NOT EXISTS dialect VARCHAR(32);

COMMENT ON COLUMN tenant_template.users.dialect IS '方言偏好（cantonese/northeastern/sichuan/henan/shandong/hunan/shaanxi/anhui），NULL=普通话';
