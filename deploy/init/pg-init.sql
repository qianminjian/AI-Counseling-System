-- MindSafe 基础设施初始化（Docker 首次启动时执行）
-- 创建 pgvector 扩展 + 公共 Schema

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 公共 Schema：存放租户注册表、全局配置（不按租户隔离的数据）
CREATE SCHEMA IF NOT EXISTS public;

COMMENT ON SCHEMA public IS 'MindSafe 公共 Schema：租户注册、全局配置';
