-- V17: 修复 DEMO2026 演示邀请码的租户归属
-- 背景：V8 插入 DEMO2026 时使用 `FROM public.tenants ... LIMIT 1`（无 ORDER BY），
--       误将其绑定到第一个租户（DEV 开发租户 00000000-0000-0000-0000-000000000001）。
--       而试用注册（TrialAuthService.validateAndConsumeInviteCode）按固定试用租户
--       90000000-0000-0000-0000-000000000001 查询邀请码，导致 DEMO2026 注册报
--       「邀请码无效或已过期」(20004)。
-- 修复：将 DEMO2026 的 tenant_id 纠正为试用租户。
-- 安全性：UPDATE 带 tenant_id <> 条件，幂等可重入；若已正确则更新 0 行，无副作用。

UPDATE tenant_template.trial_invite_codes
SET tenant_id = '90000000-0000-0000-0000-000000000001'
WHERE code = 'DEMO2026'
  AND tenant_id <> '90000000-0000-0000-0000-000000000001';
