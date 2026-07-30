-- V27: 种子数据清理（生产安全，审计项 R-05）
-- 处置裁决（2026-07-28）：
--   1. minjianq 试用账号：临时密码已明文泄露在 V6 迁移注释 → 重置为无效哈希（账号保留，
--      生产部署后由管理员重置新密码再启用登录）
--   2. MINDSAFE-TRIAL-001/002/003：仓库硬编码试用邀请码 → 全部失效，
--      生产试用邀请由运营通过管理接口（AdminController）重新签发
--   3. DEMO2026：保留（V26 已主动延期至 2027-12-31，为有意保留的演示入口）
--   4. DEV / TRIAL 租户：均保留 active（TRIAL 租户承载 DEMO2026 注册链路，
--      TrialAuthService 按固定试用租户查询邀请码，禁用会连带打断演示注册）
-- 注：V4 测试账号（小明/李老师/王老师，密码 123456）已由 V25 禁用；
--     V8 演示学生因插入条件与 V4 冲突（小明已存在则整批跳过）从未生效，无需处置。

-- ===== 1. minjianq 密码哈希失效 =====
-- '!' 前缀非 BCrypt 格式，PasswordEncoder.matches() 恒返回 false，泄露密码立即不可登录。
-- WHERE 限定原泄露哈希：若本人已改密则不覆盖新密码，幂等可重入。
UPDATE tenant_template.users
SET password_hash = '!INVALIDATED-BY-V27-LEAKED-IN-MIGRATION',
    must_change_password = true,
    updated_at = now()
WHERE user_id = '90000000-0000-0000-0000-000000000002'
  AND password_hash = '$2a$10$Zw1t498ud1DXDEBUUlYzQu2IHBy/cx69RJAbB0ZdwS8P7ziDZqF5C';

-- ===== 2. 硬编码试用邀请码失效 =====
-- 与 AdminController 停用接口一致使用 'disabled'；isUsable() 要求 status='active' 即拒绝。
UPDATE tenant_template.trial_invite_codes
SET status = 'disabled'
WHERE code IN ('MINDSAFE-TRIAL-001', 'MINDSAFE-TRIAL-002', 'MINDSAFE-TRIAL-003')
  AND status = 'active';
