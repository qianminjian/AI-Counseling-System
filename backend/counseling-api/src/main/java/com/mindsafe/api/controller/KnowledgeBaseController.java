package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.knowledge.KnowledgeBaseService;
import com.mindsafe.service.knowledge.KnowledgeCorpusIngestService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 心理知识库管理 API（AI-006：RAG 知识库）
 * <p>
 * 功能：文档摄入 / 检索测试 / 列表 / 删除
 * 权限：教师/管理员
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeCorpusIngestService corpusIngestService;
    private final AuditLogService auditLogService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                   KnowledgeCorpusIngestService corpusIngestService,
                                   AuditLogService auditLogService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.corpusIngestService = corpusIngestService;
        this.auditLogService = auditLogService;
    }

    /** 摄入文档（分块 + 嵌入） */
    @PostMapping("/documents")
    public ApiResponse<Map<String, Object>> ingest(
            @RequestBody Map<String, String> body, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        String title = body.get("title");
        String category = body.getOrDefault("category", "general");
        String content = body.get("content");
        String source = body.get("source");

        if (title == null || content == null || content.isBlank()) {
            return ApiResponse.ok(Map.of("error", "title 和 content 为必填项"));
        }

        UUID docId = knowledgeBaseService.ingestDocument(
                ctx.tenantId(), title, category, content, source);

        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_INGEST",
                "knowledge_document", docId, "title=" + title + ", category=" + category);

        return ApiResponse.ok(Map.of("docId", docId, "message", "文档摄入成功"));
    }

    /**
     * 批量入库审核语料（KB-101）
     * <p>
     * 请求体为已审核语料 Markdown 全文（data/knowledge-base/01-首批入库语料_v1.md），
     * 幂等可重复执行；crisis_intervention 类自动缓入（铁律：不进学生对话 RAG）。
     */
    @PostMapping(value = "/corpus", consumes = "text/plain;charset=UTF-8")
    public ApiResponse<KnowledgeCorpusIngestService.IngestReport> ingestCorpus(
            @RequestBody String corpusMarkdown, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        KnowledgeCorpusIngestService.IngestReport report =
                corpusIngestService.ingestCorpus(corpusMarkdown);

        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_CORPUS_INGEST",
                "knowledge_document", null,
                "parsed=" + report.parsed() + ", ingested=" + report.ingested()
                        + ", skipped=" + report.skippedExisting() + ", deferredCrisis=" + report.deferredCrisis());

        return ApiResponse.ok(report);
    }

    /** 检索测试（验证 RAG 效果） */
    @GetMapping("/search")
    public ApiResponse<List<KnowledgeBaseService.KnowledgeChunk>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int topK,
            Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        List<KnowledgeBaseService.KnowledgeChunk> results =
                knowledgeBaseService.search(ctx.tenantId(), query, topK);
        return ApiResponse.ok(results);
    }

    /** 文档列表 */
    @GetMapping("/documents")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String category,
            Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        return ApiResponse.ok(knowledgeBaseService.listDocuments(ctx.tenantId(), category));
    }

    /** 删除文档 */
    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Void> delete(@PathVariable UUID docId, Authentication auth) {
        TenantContext ctx = (TenantContext) auth.getDetails();
        knowledgeBaseService.deleteDocument(docId);
        auditLogService.log(ctx.tenantId(), ctx.userId(), "KNOWLEDGE_DELETE",
                "knowledge_document", docId, null);
        return ApiResponse.ok(null);
    }
}
