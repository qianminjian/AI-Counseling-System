-- V28 回滚：删除声纹 embedding 存储表（remote 模式声纹登录功能整体下架时执行）
-- 注意：索引随表删除自动移除；FK 约束随表删除。

DROP TABLE IF EXISTS tenant_template.voiceprint_embeddings;
