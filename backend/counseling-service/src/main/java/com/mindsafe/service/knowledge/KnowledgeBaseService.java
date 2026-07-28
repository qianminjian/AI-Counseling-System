package com.mindsafe.service.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * RAG 心理知识库服务（AI-006）
 * <p>
 * 功能：
 * 1. 文档摄入：分块（512 字符窗口）→ 向量嵌入 → pgvector 存储
 * 2. 相似度检索：query embedding → cosine similarity top-K
 * 3. Prompt 增强：将检索结果格式化为上下文注入 System Prompt
 * <p>
 * 知识分类：cbt_technique / emotion_regulation / social_skills / crisis_intervention / development_psychology
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** 分块参数 */
    private static final int CHUNK_SIZE = 512;
    private static final int CHUNK_OVERLAP = 64;

    /** 检索参数 */
    private static final int DEFAULT_TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.7;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    public KnowledgeBaseService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 摄入文档（分块 + 嵌入 + 存储）
     *
     * @param tenantId 租户 ID（null = 全局知识）
     * @param title    文档标题
     * @param category 分类
     * @param content  原始内容
     * @param source   来源
     * @return 文档 ID
     */
    @Transactional
    public UUID ingestDocument(UUID tenantId, String title, String category,
                               String content, String source) {
        UUID docId = UUID.randomUUID();

        // 1. 存储文档元数据
        jdbcTemplate.update(
                "INSERT INTO tenant_template.knowledge_documents (doc_id, tenant_id, title, category, content, source, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,now(),now())",
                docId, tenantId, title, category, content, source, "active");

        // 2. 分块
        List<String> chunks = splitIntoChunks(content);
        log.info("文档分块完成: docId={}, title={}, chunks={}", docId, title, chunks.size());

        // 3. 批量嵌入 + 存储
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] embedding = embeddingModel.embed(chunk);
            String vectorStr = toVectorString(embedding);

            jdbcTemplate.update(
                    "INSERT INTO tenant_template.knowledge_chunks (chunk_id, doc_id, tenant_id, chunk_index, content, embedding, token_count, created_at) VALUES (?,?,?,?,?,?,?,now())",
                    UUID.randomUUID(), docId, tenantId, i, chunk, vectorStr, chunk.length());
        }

        log.info("文档摄入完成: docId={}, title={}, category={}, chunks={}", docId, title, category, chunks.size());
        return docId;
    }

    /**
     * 相似度检索（RAG 核心）
     *
     * @param tenantId 租户 ID（同时检索全局 + 租户级知识）
     * @param query    查询文本
     * @param topK     返回条数
     * @return 检索结果列表
     */
    public List<KnowledgeChunk> search(UUID tenantId, String query, int topK) {
        if (topK <= 0) topK = DEFAULT_TOP_K;

        float[] queryEmbedding = embeddingModel.embed(query);
        String vectorStr = toVectorString(queryEmbedding);

        // pgvector cosine similarity: 1 - (embedding <=> query) = similarity
        String sql = """
                SELECT c.chunk_id, c.doc_id, c.content, c.chunk_index,
                       d.title, d.category,
                       1 - (c.embedding <=> ?::vector) AS similarity
                FROM tenant_template.knowledge_chunks c
                JOIN tenant_template.knowledge_documents d ON c.doc_id = d.doc_id
                WHERE d.status = 'active'
                  AND (c.tenant_id IS NULL OR c.tenant_id = ?)
                  AND 1 - (c.embedding <=> ?::vector) > ?
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new KnowledgeChunk(
                UUID.fromString(rs.getString("chunk_id")),
                UUID.fromString(rs.getString("doc_id")),
                rs.getString("content"),
                rs.getInt("chunk_index"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getDouble("similarity")
        ), vectorStr, tenantId, vectorStr, SIMILARITY_THRESHOLD, vectorStr, topK);
    }

    /**
     * 便捷方法：检索并格式化为 Prompt 上下文
     */
    public String buildRagContext(UUID tenantId, String query) {
        List<KnowledgeChunk> results = search(tenantId, query, DEFAULT_TOP_K);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("# 参考知识（来自心理辅导知识库，仅供辅助参考）\n");
        for (int i = 0; i < results.size(); i++) {
            KnowledgeChunk chunk = results.get(i);
            sb.append(String.format("[%d] (%s) %s\n%s\n\n",
                    i + 1, chunk.category(), chunk.title(), chunk.content()));
        }
        sb.append("注意：以上知识仅供参考，请结合学生实际情况灵活运用，不要照搬。\n");
        return sb.toString();
    }

    /**
     * 删除文档（级联删除分块）
     */
    @Transactional
    public void deleteDocument(UUID docId) {
        jdbcTemplate.update("DELETE FROM tenant_template.knowledge_documents WHERE doc_id = ?", docId);
        log.info("知识文档已删除: docId={}", docId);
    }

    /**
     * 按分类列出文档
     */
    public List<Map<String, Object>> listDocuments(UUID tenantId, String category) {
        String sql = "SELECT doc_id, title, category, source, status, created_at FROM tenant_template.knowledge_documents WHERE (tenant_id IS NULL OR tenant_id = ?) AND status = 'active'";
        List<Object> params = new ArrayList<>(List.of(tenantId));
        if (category != null && !category.isBlank()) {
            sql += " AND category = ?";
            params.add(category);
        }
        sql += " ORDER BY created_at DESC";
        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    // ===== 内部方法 =====

    /**
     * 文本分块（滑动窗口 + 重叠）
     */
    private List<String> splitIntoChunks(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            // 尝试在句子边界切割
            if (end < text.length()) {
                int lastBreak = text.lastIndexOf('\n', end);
                if (lastBreak > start + CHUNK_SIZE / 2) {
                    end = lastBreak;
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = end - CHUNK_OVERLAP;
            if (start >= text.length()) break;
        }
        return chunks.stream().filter(c -> !c.isBlank()).toList();
    }

    /**
     * float[] → pgvector 格式字符串 "[0.1,0.2,...]"
     */
    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** 检索结果 */
    public record KnowledgeChunk(
            UUID chunkId, UUID docId, String content, int chunkIndex,
            String title, String category, double similarity) {
    }
}

