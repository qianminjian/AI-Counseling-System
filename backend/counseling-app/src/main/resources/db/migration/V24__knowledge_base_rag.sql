-- V24: RAG 心理知识库（AI-006）
-- pgvector 向量存储 + 文档分块

CREATE EXTENSION IF NOT EXISTS vector;

-- 知识库文档（原始文档元数据）
CREATE TABLE IF NOT EXISTS tenant_template.knowledge_documents (
    doc_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID,  -- NULL = 全局知识（跨租户共享）
    title VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL DEFAULT 'general',
    -- category: cbt_technique / emotion_regulation / social_skills / crisis_intervention / development_psychology
    source VARCHAR(200),
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 文档分块 + 向量嵌入
CREATE TABLE IF NOT EXISTS tenant_template.knowledge_chunks (
    chunk_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES tenant_template.knowledge_documents(doc_id) ON DELETE CASCADE,
    tenant_id UUID,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536),  -- OpenAI text-embedding-3-small 维度
    token_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 向量相似度检索索引（IVFFlat，适合 < 100k 行）
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding
    ON tenant_template.knowledge_chunks
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- 按文档/租户查询
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_doc
    ON tenant_template.knowledge_chunks (doc_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_tenant
    ON tenant_template.knowledge_documents (tenant_id, category, status);
