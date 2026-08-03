package com.mindsafe.service.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseService 单测（AI-006 / KB-102）。
 * <p>
 * 覆盖：摄入（draft 默认 + 分块 + 向量写入）/ 检索（published 过滤）/ RAG 上下文格式化 /
 * 审核状态查询与落库（V30）/ 文档列表（审核工作流可见性）。
 * <p>
 * 注：JdbcTemplate 为 varargs 方法，Mockito 需按实际参数个数逐一匹配。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 知识库服务")
class KnowledgeBaseServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private EmbeddingModel embeddingModel;

    private KnowledgeBaseService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(jdbcTemplate, embeddingModel);
    }

    @Nested
    @DisplayName("ingestDocument 摄入")
    class Ingest {

        @Test
        @DisplayName("新摄入默认 draft（KB-102 铁律：须过审才可检索）")
        void newDocument_defaultsToDraft() {
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

            service.ingestDocument(tenantId, "情绪认知", "emotion_regulation", "短内容", "manual");

            // 文档元数据 INSERT：7 个 varargs（docId,tenantId,title,category,content,source,status）
            verify(jdbcTemplate).update(
                    argThat((String sql) -> sql.contains("INSERT INTO tenant_template.knowledge_documents")),
                    any(), any(), any(), any(), any(), any(), argThat(arg -> "draft".equals(arg)));
        }

        @Test
        @DisplayName("长文本分块：512 字符窗口产生多个 chunk，每个 chunk 单独嵌入写库")
        void longContent_chunkedAndEmbedded() {
            String longContent = "情绪调节知识。".repeat(200); // 1400 字符 > 512
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.5f});

            service.ingestDocument(tenantId, "长文档", "cbt_technique", longContent, "corpus");

            // 1 次文档元数据写入
            verify(jdbcTemplate, times(1)).update(
                    argThat((String sql) -> sql.contains("knowledge_documents")),
                    any(), any(), any(), any(), any(), any(), any());
            // ≥2 次分块写入（7 个 varargs：chunkId,docId,tenantId,index,content,embedding,tokenCount）
            verify(jdbcTemplate, atLeast(2)).update(
                    argThat((String sql) -> sql.contains("knowledge_chunks")),
                    any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("向量写入格式为 pgvector 字符串 [x,y]")
        void vectorStringFormat() {
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

            service.ingestDocument(tenantId, "标题", "category", "内容", "manual");

            // embedding 位于第 6 个 vararg
            verify(jdbcTemplate).update(
                    argThat((String sql) -> sql.contains("knowledge_chunks")),
                    any(), any(), any(), any(), any(), argThat(arg -> "[0.1,0.2]".equals(arg)), any());
        }
    }

    @Nested
    @DisplayName("search 检索")
    class Search {

        /** 检索调用固定 6 个 varargs：vector, tenantId, vector, threshold, vector, topK */
        private void stubQuery(Object result) {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    any(), any(), any(), any(), any(), any())).thenReturn((List) result);
        }

        @Test
        @DisplayName("检索 SQL 仅命中 published（KB-102 铁律）")
        void searchFiltersPublished() {
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
            stubQuery(List.of());

            service.search(tenantId, "我很难过", 3);

            verify(jdbcTemplate).query(
                    argThat((String sql) -> sql.contains("d.status = 'published'")),
                    any(RowMapper.class), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("topK<=0 时回退默认 3")
        void invalidTopK_defaults() {
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
            stubQuery(List.of());

            service.search(tenantId, "查询", 0);

            verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                    any(), any(), any(), any(), any(), argThat(arg -> Integer.valueOf(3).equals(arg)));
        }

        @Test
        @DisplayName("检索结果映射为 KnowledgeChunk")
        void mapsRowsToChunks() {
            UUID chunkId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    any(), any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> {
                        RowMapper<KnowledgeBaseService.KnowledgeChunk> mapper = inv.getArgument(1);
                        var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                        when(rs.getString("chunk_id")).thenReturn(chunkId.toString());
                        when(rs.getString("doc_id")).thenReturn(docId.toString());
                        when(rs.getString("content")).thenReturn("情绪没有好坏");
                        when(rs.getInt("chunk_index")).thenReturn(0);
                        when(rs.getString("title")).thenReturn("KB-001");
                        when(rs.getString("category")).thenReturn("emotion_management");
                        when(rs.getDouble("similarity")).thenReturn(0.85);
                        return List.of(mapper.mapRow(rs, 0));
                    });

            List<KnowledgeBaseService.KnowledgeChunk> results = service.search(tenantId, "难过", 3);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("KB-001");
            assertThat(results.get(0).similarity()).isEqualTo(0.85);
        }
    }

    @Nested
    @DisplayName("buildRagContext 上下文格式化")
    class RagContext {

        @Test
        @DisplayName("无检索结果 → 空串")
        void emptyResults_emptyString() {
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    any(), any(), any(), any(), any(), any())).thenReturn(List.of());

            assertThat(service.buildRagContext(tenantId, "查询")).isEmpty();
        }

        @Test
        @DisplayName("有结果 → 编号列表 + 安全尾注")
        void formattedWithSafetyFooter() {
            UUID chunkId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    any(), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(new KnowledgeBaseService.KnowledgeChunk(
                            chunkId, docId, "内容正文", 0, "KB-001", "emotion_management", 0.9)));

            String context = service.buildRagContext(tenantId, "查询");

            assertThat(context)
                    .contains("参考知识")
                    .contains("[1] (emotion_management) KB-001")
                    .contains("不要照搬");
        }
    }

    @Nested
    @DisplayName("审核工作流（KB-102，V30）")
    class ReviewWorkflow {

        @Test
        @DisplayName("findDocumentStatus：返回 DB 真实状态")
        void findDocumentStatus() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), (Object) any()))
                    .thenReturn(List.of("in_review"));

            assertThat(service.findDocumentStatus(UUID.randomUUID())).isEqualTo("in_review");
        }

        @Test
        @DisplayName("findDocumentStatus：无记录 → null")
        void findDocumentStatus_missing() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), (Object) any()))
                    .thenReturn(List.of());

            assertThat(service.findDocumentStatus(UUID.randomUUID())).isNull();
        }

        @Test
        @DisplayName("transitionReviewStatus：UPDATE 落库返回行数（SQL 含 COALESCE 保留旧值 + reviewed_at）")
        void transitionReturnsRowCount() {
            // 6 个 varargs：targetStatus, gradeBand, sourceType, evidenceLevel, reviewer, docId
            when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

            int rows = service.transitionReviewStatus(UUID.randomUUID(), "published",
                    "low", "textbook", "A", "张老师");

            assertThat(rows).isEqualTo(1);
            verify(jdbcTemplate).update(
                    argThat((String sql) -> sql.contains("COALESCE") && sql.contains("reviewed_at")),
                    eq("published"), eq("low"), eq("textbook"), eq("A"), eq("张老师"), any());
        }

        @Test
        @DisplayName("listDocuments：含全审核状态（仅排除 deprecated），供审核工作流使用")
        void listDocuments_excludesOnlyDeprecated() {
            when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(List.of(Map.of("title", "文档A")));

            List<Map<String, Object>> docs = service.listDocuments(tenantId, null);

            assertThat(docs).hasSize(1);
            verify(jdbcTemplate).queryForList(
                    argThat((String sql) -> sql.contains("status <> 'deprecated'")
                            && sql.contains("grade_band") && sql.contains("reviewer")),
                    eq(tenantId));
        }

        @Test
        @DisplayName("listDocuments：按分类过滤")
        void listDocuments_byCategory() {
            when(jdbcTemplate.queryForList(anyString(), (Object) any(), (Object) any())).thenReturn(List.of());

            service.listDocuments(tenantId, "cbt_technique");

            verify(jdbcTemplate).queryForList(
                    argThat((String sql) -> sql.contains("AND category = ?")),
                    eq(tenantId), eq("cbt_technique"));
        }

        @Test
        @DisplayName("deleteDocument：删除文档（分块级联由外键保证）")
        void deleteDocument() {
            UUID docId = UUID.randomUUID();

            service.deleteDocument(docId);

            verify(jdbcTemplate).update(
                    argThat((String sql) -> sql.contains("DELETE FROM tenant_template.knowledge_documents")),
                    eq(docId));
        }
    }
}
