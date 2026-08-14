-- V49 rollback: 字段加密容量扩展（B-05，2026-08-14）
-- 回滚：恢复原列类型。⚠ 若存量已为密文（启用加密后写入），恢复 VARCHAR(20)/VARCHAR(2048)
-- 将因超长失败——回滚前须先解密回明文（或走 restore.sh 备份恢复）。

ALTER TABLE tenant_template.toc_family_accounts
    ALTER COLUMN phone TYPE VARCHAR(20);

ALTER TABLE tenant_template.teacher_notes
    ALTER COLUMN content TYPE VARCHAR(2048);
