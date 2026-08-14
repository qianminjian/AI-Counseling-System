-- V47 rollback: audit_logs.tenant_id 恢复 NOT NULL（专题 D 合规留痕链基建）
-- 回滚语义：恢复 NOT NULL 约束（撤销「放开 NOT NULL」）。
-- ⚠ 系统级/平台级审计行（tenant_id IS NULL）存在时 SET NOT NULL 将失败——
-- 回滚前须人工处置 NULL 行（补租户值或归档），否则改用 restore.sh 备份恢复兜底。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tenant_template.audit_logs WHERE tenant_id IS NULL) THEN
        RAISE EXCEPTION '存在 % 行 tenant_id IS NULL（系统级审计），恢复 NOT NULL 前须先处置；请人工处理或走 restore.sh 备份恢复',
            (SELECT count(*) FROM tenant_template.audit_logs WHERE tenant_id IS NULL);
    END IF;
END $$;

ALTER TABLE tenant_template.audit_logs ALTER COLUMN tenant_id SET NOT NULL;
