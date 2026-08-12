package com.mindsafe.service.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 语料批量入库服务测试（KB-101）
 * <p>
 * 核心验证：危机类缓入铁律 + 幂等跳过 + 全局知识域入库映射。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeCorpusIngestServiceTest {

    private static final String CORPUS = """
            ### KB-001 认知扭曲：非黑即白思维
            - category: cbt_technique | grade_band: all | source_type: textbook | evidence_level: 高
            - 来源：Beck 认知扭曲分类

            非黑即白思维指用两个极端看事情。

            ### KB-016 情绪命名
            - category: emotion_regulation | grade_band: all | source_type: official | evidence_level: 高
            - 来源：《心理健康素养十条》

            给情绪起名字能帮助孩子平静。

            ### KB-053 危机风险信号识别（仅教师侧）
            - category: crisis_intervention | grade_band: all | source_type: official | evidence_level: 高
            - 来源：WHO 公开心理健康资料

            需要警觉的信号说明。
            """;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("危机类缓入：crisis_intervention 不调用入库（铁律：不进学生对话 RAG）")
    void crisisEntriesDeferredNeverIngested() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(0L);
        when(knowledgeBaseService.ingestDocument(isNull(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        KnowledgeCorpusIngestService service =
                new KnowledgeCorpusIngestService(knowledgeBaseService, jdbcTemplate);

        KnowledgeCorpusIngestService.IngestReport report = service.ingestCorpus(CORPUS);

        assertThat(report.parsed()).isEqualTo(3);
        assertThat(report.ingested()).isEqualTo(2);
        assertThat(report.deferredCrisis()).isEqualTo(1);
        verify(knowledgeBaseService, never()).ingestDocument(
                any(), contains("KB-053"), anyString(), anyString(), anyString());
        verify(knowledgeBaseService, never()).ingestDocument(
                any(), anyString(), eq("crisis_intervention"), anyString(), anyString());
    }

    @Test
    @DisplayName("幂等：已存在同标题文档时跳过，不重复入库")
    void existingTitlesSkipped() {
        // KB-001 已存在（count=1），KB-016 不存在
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("KB-001 认知扭曲：非黑即白思维")))
                .thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("KB-016 情绪命名")))
                .thenReturn(0L);
        when(knowledgeBaseService.ingestDocument(isNull(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        KnowledgeCorpusIngestService service =
                new KnowledgeCorpusIngestService(knowledgeBaseService, jdbcTemplate);

        KnowledgeCorpusIngestService.IngestReport report = service.ingestCorpus(CORPUS);

        assertThat(report.ingested()).isEqualTo(1);
        assertThat(report.skippedExisting()).isEqualTo(1);
        assertThat(report.ingestedTitles()).containsExactly("KB-016 情绪命名");
        verify(knowledgeBaseService, times(1)).ingestDocument(
                isNull(), eq("KB-016 情绪命名"), eq("emotion_regulation"),
                contains("给情绪起名字"), eq("《心理健康素养十条》"));
    }

    @Test
    @DisplayName("G-P0-2 幂等查重：published 状态文档同样命中查重（V30 后 published 口径）")
    void publishedStatusHitsDedupCheck() {
        // KB-001 以 published 状态存在（count=1），KB-016 不存在
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("KB-001 认知扭曲：非黑即白思维")))
                .thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("KB-016 情绪命名")))
                .thenReturn(0L);
        when(knowledgeBaseService.ingestDocument(isNull(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        KnowledgeCorpusIngestService service =
                new KnowledgeCorpusIngestService(knowledgeBaseService, jdbcTemplate);

        KnowledgeCorpusIngestService.IngestReport report = service.ingestCorpus(CORPUS);

        // published 状态文档命中幂等跳过（与 active 同口径）
        assertThat(report.ingested()).isEqualTo(1);
        assertThat(report.skippedExisting()).isEqualTo(1);
        assertThat(report.ingestedTitles()).containsExactly("KB-016 情绪命名");

        // 查重 SQL 谓词已覆盖 active+published（G-P0-2 收敛断言，防旧 status='active' 回归）
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(sqlCaptor.capture(), eq(Long.class), anyString());
        assertThat(sqlCaptor.getAllValues())
                .hasSize(2)
                .allMatch(sql -> sql.contains("status IN ('active','published')"))
                .noneMatch(sql -> sql.contains("status = 'active'") || sql.contains("status='active'"));
    }

    @Test
    @DisplayName("入库映射：tenantId=null 全局域，content 首行带 grade_band 标注")
    void ingestMappingUsesGlobalTenantAndAnnotatedContent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(0L);
        when(knowledgeBaseService.ingestDocument(isNull(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        KnowledgeCorpusIngestService service =
                new KnowledgeCorpusIngestService(knowledgeBaseService, jdbcTemplate);

        service.ingestCorpus(CORPUS);

        verify(knowledgeBaseService).ingestDocument(
                isNull(), eq("KB-001 认知扭曲：非黑即白思维"), eq("cbt_technique"),
                startsWith("【适用年级段 grade_band: all】"), eq("Beck 认知扭曲分类"));
    }

    @Test
    @DisplayName("空语料：返回全零报告，不触发任何入库")
    void emptyCorpusReturnsZeroReport() {
        KnowledgeCorpusIngestService service =
                new KnowledgeCorpusIngestService(knowledgeBaseService, jdbcTemplate);

        KnowledgeCorpusIngestService.IngestReport report = service.ingestCorpus("");

        assertThat(report.parsed()).isZero();
        assertThat(report.ingested()).isZero();
        verifyNoInteractions(knowledgeBaseService);
    }
}
