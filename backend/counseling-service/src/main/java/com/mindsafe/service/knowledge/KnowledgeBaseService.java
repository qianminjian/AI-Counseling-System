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

    /** 关键词路最大词元数（防止超长查询生成巨量 LIKE 条件） */
    private static final int MAX_KEYWORD_TOKENS = 8;

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

        // 1. 存储文档元数据（KB-102 铁律：新摄入默认 draft，须过审核发布后才可被 RAG 检索）
        jdbcTemplate.update(
                "INSERT INTO tenant_template.knowledge_documents (doc_id, tenant_id, title, category, content, source, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,now(),now())",
                docId, tenantId, title, category, content, source, "draft");

        // 2. 分块
        List<String> chunks = splitIntoChunks(content);
        log.info("文档分块完成: docId={}, title={}, chunks={}", docId, title, chunks.size());

        // 3. 批量嵌入 + 存储
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] embedding = embeddingModel.embed(chunk);
            String vectorStr = toVectorString(embedding);

            // BUG-KB-01：embedding 参数须 ::vector cast（search 已有，INSERT 遗漏——生产实证
            // BadSqlGrammarException: column "embedding" is of type vector but expression is of type character varying）
            jdbcTemplate.update(
                    "INSERT INTO tenant_template.knowledge_chunks (chunk_id, doc_id, tenant_id, chunk_index, content, embedding, token_count, created_at) VALUES (?,?,?,?,?,?::vector,?,now())",
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
        // KB-102 铁律：仅 published 内容可被检索（V30 后 active 已迁移为 published）
        String sql = """
                SELECT c.chunk_id, c.doc_id, c.content, c.chunk_index,
                       d.title, d.category,
                       1 - (c.embedding <=> ?::vector) AS similarity
                FROM tenant_template.knowledge_chunks c
                JOIN tenant_template.knowledge_documents d ON c.doc_id = d.doc_id
                WHERE d.status = 'published'
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
     * 关键词检索（KB-103 混合检索关键词路：ILIKE 命中数排序，不依赖 embedding）
     * <p>
     * 与 {@link #search} 同纪律：仅 published 可被检索；全局 + 租户级同查。
     * 评分 = 各词在正文/标题的命中数之和（BM25 的简化替代，满足小语料库场景）。
     *
     * @param tenantId 租户 ID
     * @param query    查询文本（提取 ≥2 字符的连续词元，最多 5 个）
     * @param topK     返回条数
     * @return 检索结果（similarity 字段存命中数，仅供排序；RRF 只用名次）
     */
    public List<KnowledgeChunk> searchKeyword(UUID tenantId, String query, int topK) {
        List<String> tokens = extractKeywordTokens(query);
        if (tokens.isEmpty()) return List.of();
        if (topK <= 0) topK = DEFAULT_TOP_K;

        StringBuilder scoreExpr = new StringBuilder("0");
        StringBuilder matchExpr = new StringBuilder("FALSE");
        List<Object> selectParams = new ArrayList<>();
        List<Object> whereParams = new ArrayList<>();
        for (String t : tokens) {
            String like = "%" + t + "%";
            scoreExpr.append(" + CASE WHEN c.content ILIKE ? THEN 1 ELSE 0 END")
                     .append(" + CASE WHEN d.title ILIKE ? THEN 1 ELSE 0 END");
            selectParams.add(like);
            selectParams.add(like);
            matchExpr.append(" OR c.content ILIKE ? OR d.title ILIKE ?");
            whereParams.add(like);
            whereParams.add(like);
        }

        String sql = """
                SELECT c.chunk_id, c.doc_id, c.content, c.chunk_index,
                       d.title, d.category,
                       (%s)::float8 AS relevance
                FROM tenant_template.knowledge_chunks c
                JOIN tenant_template.knowledge_documents d ON c.doc_id = d.doc_id
                WHERE d.status = 'published'
                  AND (c.tenant_id IS NULL OR c.tenant_id = ?)
                  AND (%s)
                ORDER BY relevance DESC
                LIMIT ?
                """.formatted(scoreExpr, matchExpr);

        List<Object> params = new ArrayList<>(selectParams);
        params.addAll(whereParams);
        params.add(tenantId);
        params.add(topK);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new KnowledgeChunk(
                UUID.fromString(rs.getString("chunk_id")),
                UUID.fromString(rs.getString("doc_id")),
                rs.getString("content"),
                rs.getInt("chunk_index"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getDouble("relevance")
        ), params.toArray());
    }

    /** 关键词元提取：短串直接成词；长中文段无分词可用，按 2-gram 滑窗保底命中具体词汇 */
    static List<String> extractKeywordTokens(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\\u4e00-\\u9fa5A-Za-z0-9]{2,}").matcher(query);
        while (m.find() && tokens.size() < MAX_KEYWORD_TOKENS) {
            String run = m.group();
            boolean cjk = run.charAt(0) >= '\u4e00' && run.charAt(0) <= '\u9fa5';
            if (!cjk || run.length() <= 4) {
                addToken(tokens, run);
            } else {
                for (int i = 0; i + 2 <= run.length() && tokens.size() < MAX_KEYWORD_TOKENS; i++) {
                    addToken(tokens, run.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private static void addToken(List<String> tokens, String token) {
        if (tokens.size() < MAX_KEYWORD_TOKENS && !tokens.contains(token)) {
            tokens.add(token);
        }
    }

    /**
     * 删除文档（级联删除分块）。B3 修复：加 tenant_id 过滤防跨租户越权。
     */
    @Transactional
    public void deleteDocument(UUID tenantId, UUID docId) {
        jdbcTemplate.update("DELETE FROM tenant_template.knowledge_documents WHERE tenant_id = ? AND doc_id = ?", tenantId, docId);
        log.info("知识文档已删除: tenantId={}, docId={}", tenantId, docId);
    }

    /**
     * 按分类列出文档（含全部审核状态，供教师后台审核工作流使用）
     */
    public List<Map<String, Object>> listDocuments(UUID tenantId, String category) {
        String sql = "SELECT doc_id, title, category, source, status, grade_band, evidence_level, reviewer, created_at FROM tenant_template.knowledge_documents WHERE (tenant_id IS NULL OR tenant_id = ?) AND status <> 'deprecated'";
        List<Object> params = new ArrayList<>(List.of(tenantId));
        if (category != null && !category.isBlank()) {
            sql += " AND category = ?";
            params.add(category);
        }
        sql += " ORDER BY created_at DESC";
        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    /**
     * 查询文档当前审核状态（DB 真实值，不信请求体）。B3 修复：加 tenant_id 过滤。
     *
     * @return status 字符串，文档不存在时返回 null
     */
    public String findDocumentStatus(UUID tenantId, UUID docId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT status FROM tenant_template.knowledge_documents WHERE tenant_id = ? AND doc_id = ?",
                String.class, tenantId, docId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 审核状态转移落库（KB-102，V30 审核字段）。B3 修复：加 tenant_id 过滤。
     * <p>
     * 门禁校验由调用方（KnowledgeBaseController + ReviewGateValidator）完成后调用；
     * 审核字段 COALESCE 保留旧值（允许分步补齐）。
     *
     * @return 更新行数（0=文档不存在或不属该租户）
     */
    @Transactional
    public int transitionReviewStatus(UUID tenantId, UUID docId, String targetStatus,
                                      String gradeBand, String sourceType,
                                      String evidenceLevel, String reviewer) {
        int rows = jdbcTemplate.update(
                """
                UPDATE tenant_template.knowledge_documents
                SET status = ?,
                    grade_band = COALESCE(?, grade_band),
                    source_type = COALESCE(?, source_type),
                    evidence_level = COALESCE(?, evidence_level),
                    reviewer = COALESCE(?, reviewer),
                    reviewed_at = now(),
                    updated_at = now()
                WHERE tenant_id = ? AND doc_id = ?
                """,
                targetStatus, gradeBand, sourceType, evidenceLevel, reviewer, tenantId, docId);
        log.info("知识审核状态落库: tenantId={}, docId={}, targetStatus={}, reviewer={}, rows={}",
                tenantId, docId, targetStatus, reviewer, rows);
        return rows;
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
            // 末窗口已覆盖到文本末尾 → 终止（否则 start=end-OVERLAP 永不推进，死循环 OOM）
            if (end >= text.length()) break;
            start = end - CHUNK_OVERLAP;
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

