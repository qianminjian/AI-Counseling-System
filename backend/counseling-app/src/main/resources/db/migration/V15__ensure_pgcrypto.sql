-- V15: 确保 pgcrypto 扩展存在（学校初始化工具依赖 crypt/gen_salt）
CREATE EXTENSION IF NOT EXISTS pgcrypto;
