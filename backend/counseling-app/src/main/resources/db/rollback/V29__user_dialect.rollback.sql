-- V29 回滚：删除用户方言偏好列（方言 TTS 功能整体下架时执行）
-- 注意：已有方言值将一并删除（不可逆），回滚前如需保留请先导出。

ALTER TABLE tenant_template.users DROP COLUMN IF EXISTS dialect;
