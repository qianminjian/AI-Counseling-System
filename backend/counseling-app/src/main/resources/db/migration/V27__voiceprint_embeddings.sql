-- 声纹 embedding 向量存储（remote 模式：前端提取 embedding 传服务端比对）
-- 隐私：仅存 256-dim 特征向量，不存原始音频，不可逆向还原声音
-- 多租户：tenant_template schema，随租户隔离

CREATE TABLE IF NOT EXISTS tenant_template.voiceprint_embeddings (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    embedding       JSONB NOT NULL,           -- 256-dim float 数组 JSON
    sample_index    SMALLINT NOT NULL DEFAULT 0, -- 同一用户第几段样本
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_vp_user FOREIGN KEY (user_id)
        REFERENCES tenant_template.users(user_id) ON DELETE CASCADE
);

-- 按用户查询（登录时 1:N 比对）
CREATE INDEX idx_vp_embeddings_user ON tenant_template.voiceprint_embeddings(user_id);
-- 按租户清理
CREATE INDEX idx_vp_embeddings_tenant ON tenant_template.voiceprint_embeddings(tenant_id);

COMMENT ON TABLE tenant_template.voiceprint_embeddings IS
    '声纹特征向量（remote模式）：仅存256-dim embedding，不存音频，PIPL数据最小化';
