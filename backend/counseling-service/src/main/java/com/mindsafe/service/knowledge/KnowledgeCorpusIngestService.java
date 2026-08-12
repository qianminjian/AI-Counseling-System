package com.mindsafe.service.knowledge;

import com.mindsafe.service.knowledge.KnowledgeCorpusParser.CorpusEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 首批语料批量入库服务（KB-101）
 * <p>
 * 职责：解析已审核的语料 Markdown → 逐条调用 {@link KnowledgeBaseService#ingestDocument} 入全局知识域。
 * <p>
 * 三条纪律（15 §12.3、49 §6.3、语料文件头铁律）：
 * 1. crisis_intervention 类（KB-053~062）**暂缓入库**——现表结构无检索域区分字段，
 *    入库会进入学生对话 RAG 检索源，违反"危机类仅教师侧"铁律；待 KB-102 元数据字段落地后再入；
 * 2. 幂等——按入库标题（KB-NNN 前缀）查重，已存在则跳过，支持重复执行；
 * 3. 全局知识域入库（tenantId=null），全部租户共享检索。
 */
@Service
public class KnowledgeCorpusIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusIngestService.class);

    /** 危机干预类：暂缓入库（铁律：不进学生对话 RAG，待 KB-102 检索域字段落地） */
    static final String CATEGORY_CRISIS = "crisis_intervention";

    private final KnowledgeBaseService knowledgeBaseService;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeCorpusIngestService(KnowledgeBaseService knowledgeBaseService,
                                        JdbcTemplate jdbcTemplate) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量入库语料（幂等，可重复执行）
     *
     * @param corpusMarkdown 已审核的语料 Markdown 全文
     * @return 入库结果统计
     */
    public IngestReport ingestCorpus(String corpusMarkdown) {
        List<CorpusEntry> entries = KnowledgeCorpusParser.parse(corpusMarkdown);
        if (entries.isEmpty()) {
            return new IngestReport(0, 0, 0, 0, List.of());
        }

        int ingested = 0, skippedExisting = 0, deferredCrisis = 0;
        List<String> ingestedTitles = new ArrayList<>();

        for (CorpusEntry entry : entries) {
            if (CATEGORY_CRISIS.equals(entry.category())) {
                deferredCrisis++;
                log.info("危机类语料暂缓入库（铁律：不进学生对话 RAG）: {}", entry.documentTitle());
                continue;
            }
            if (documentExists(entry.documentTitle())) {
                skippedExisting++;
                continue;
            }
            UUID docId = knowledgeBaseService.ingestDocument(
                    null, entry.documentTitle(), entry.category(),
                    entry.documentContent(), entry.source());
            ingested++;
            ingestedTitles.add(entry.documentTitle());
            log.info("语料入库完成: docId={}, title={}", docId, entry.documentTitle());
        }

        IngestReport report = new IngestReport(
                entries.size(), ingested, skippedExisting, deferredCrisis, ingestedTitles);
        log.info("语料批量入库结束: 解析={}, 新入={}, 已存在跳过={}, 危机类缓入={}",
                report.parsed(), report.ingested(), report.skippedExisting(), report.deferredCrisis());
        return report;
    }

    /** 幂等查重：全局知识域中是否已存在同标题文档（G-P0-2：V30 后审核状态 active→published，查重须同时覆盖，否则已发布文档重复摄入绕过幂等） */
    private boolean documentExists(String title) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenant_template.knowledge_documents WHERE tenant_id IS NULL AND title = ? AND status IN ('active','published')",
                Long.class, title);
        return count != null && count > 0;
    }

    /** 批量入库结果 */
    public record IngestReport(
            int parsed,             // 解析出的条目总数
            int ingested,           // 本次新入库条数
            int skippedExisting,    // 已存在跳过条数（幂等）
            int deferredCrisis,     // 危机类缓入条数
            List<String> ingestedTitles) {
    }
}
