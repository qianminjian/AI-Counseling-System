-- V42 rollback: toC 家庭账号与孩子档案表（TOC-001/002，doing/85 §四）
-- 回滚：删除孩子档案（引用家庭账号）与家庭账号两表，索引随表删除

DROP TABLE IF EXISTS tenant_template.toc_child_profiles;
DROP TABLE IF EXISTS tenant_template.toc_family_accounts;
