-- 集成测试数据库初始化（Testcontainers 启动后、Flyway 迁移前执行）
-- 与 deploy/init/pg-init.sql 保持一致：预装迁移脚本依赖的扩展
-- （V1 起使用 uuid_generate_v4()，需 uuid-ossp；V15 使用 pgcrypto；向量检索需 vector）

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;
