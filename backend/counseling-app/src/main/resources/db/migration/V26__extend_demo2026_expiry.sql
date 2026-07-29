-- V26: 延长 DEMO2026 演示邀请码有效期至 2027 年底
UPDATE tenant_template.trial_invite_codes
SET expires_at = '2027-12-31 23:59:59+08'
WHERE code = 'DEMO2026';
