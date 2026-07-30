package com.mindsafe.service.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索 RRF 与 groundedness 回收（KB-103，design/49 P2/P3）
 * <p>
 * <ul>
 *   <li>混合检索：向量(0.6) + 关键词(0.4) → RRF 融合排序</li>
 *   <li>groundedness 回收：回复是否基于检索内容，低分反哺内容补全</li>
 *   <li>未命中查询日志：高频未命中 → 内容缺口清单</li>
 * </ul>
 * 纯函数实现。接线时由 RagAdvisorService 检索流程 + 会话结束异步任务消费。
 */
@Component
public class HybridRetrievalService {

    /** 向量检索权重 */
    public static final double VECTOR_WEIGHT = 0.6;

    /** 关键词检索权重 */
    public static final double KEYWORD_WEIGHT = 0.4;

    /** RRF 常数 k（标准值 60） */
    public static final int RRF_K = 60;

    /** groundedness 低分阈值（低于此值视为检索没帮上） */
    public static final double LOW_GROUNDEDNESS_THRESHOLD = 0.4;

    /** 未命中查询频率阈值（达到即列入缺口清单） */
    public static final int MISS_FREQUENCY_THRESHOLD = 3;

    // ==================== RRF 融合 ====================

    /** 检索结果条目 */
    public record RetrievalHit(
            String docId,
            String title,
            double score,
            String source
    ) {
    }

    /** RRF 融合结果 */
    public record FusedResult(
            String docId,
            String title,
            double rrfScore,
            boolean fromVector,
            boolean fromKeyword
    ) {
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合两路检索结果。
     * 公式：RRF(d) = Σ weight_i / (k + rank_i(d))
     *
     * @param vectorResults  向量检索结果（已按相似度降序）
     * @param keywordResults 关键词检索结果（已按 BM25 降序）
     * @param topK           返回前 K 条
     * @return 融合排序结果
     */
    public List<FusedResult> fuseRRF(List<RetrievalHit> vectorResults,
                                     List<RetrievalHit> keywordResults,
                                     int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        Map<String, boolean[]> sources = new HashMap<>(); // [fromVector, fromKeyword]

        // 向量路：权重 0.6
        if (vectorResults != null) {
            for (int rank = 0; rank < vectorResults.size(); rank++) {
                RetrievalHit hit = vectorResults.get(rank);
                double contribution = VECTOR_WEIGHT / (RRF_K + rank + 1);
                rrfScores.merge(hit.docId(), contribution, Double::sum);
                titles.putIfAbsent(hit.docId(), hit.title());
                sources.computeIfAbsent(hit.docId(), k -> new boolean[2])[0] = true;
            }
        }

        // 关键词路：权重 0.4
        if (keywordResults != null) {
            for (int rank = 0; rank < keywordResults.size(); rank++) {
                RetrievalHit hit = keywordResults.get(rank);
                double contribution = KEYWORD_WEIGHT / (RRF_K + rank + 1);
                rrfScores.merge(hit.docId(), contribution, Double::sum);
                titles.putIfAbsent(hit.docId(), hit.title());
                sources.computeIfAbsent(hit.docId(), k -> new boolean[2])[1] = true;
            }
        }

        // 排序取 topK
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    boolean[] src = sources.get(e.getKey());
                    return new FusedResult(e.getKey(), titles.get(e.getKey()),
                            e.getValue(), src[0], src[1]);
                })
                .toList();
    }

    // ==================== groundedness 回收 ====================

    /** groundedness 评估结果 */
    public record GroundednessResult(
            String sessionId,
            double groundednessScore,
            boolean effective,
            String feedback
    ) {
    }

    /**
     * 评估 RAG 注入的 groundedness（回复是否基于检索内容）。
     * 简化实现：基于检索片段被引用比例。
     *
     * @param sessionId       会话 ID
     * @param retrievedChunks 检索到的片段数
     * @param citedChunks     回复中实际引用/体现的片段数
     * @return groundedness 结果
     */
    public GroundednessResult evaluateGroundedness(String sessionId,
                                                   int retrievedChunks, int citedChunks) {
        if (retrievedChunks == 0) {
            return new GroundednessResult(sessionId, 0, false, "无检索结果");
        }

        double score = (double) citedChunks / retrievedChunks;
        boolean effective = score >= LOW_GROUNDEDNESS_THRESHOLD;

        String feedback;
        if (score >= 0.7) {
            feedback = "检索内容被充分利用";
        } else if (effective) {
            feedback = "检索内容部分被使用";
        } else {
            feedback = "检索没帮上/被忽略，反哺内容补全与检索调优";
        }

        return new GroundednessResult(sessionId, score, effective, feedback);
    }

    // ==================== 未命中查询日志 ====================

    /** 内容缺口条目 */
    public record ContentGap(
            String query,
            int missCount,
            String suggestedCategory
    ) {
    }

    /**
     * 从查询日志中识别高频未命中查询（内容缺口）。
     *
     * @param missedQueries 未命中查询列表（每次检索无结果时记录）
     * @return 缺口清单（按频率降序）
     */
    public List<ContentGap> identifyContentGaps(List<String> missedQueries) {
        if (missedQueries == null || missedQueries.isEmpty()) return List.of();

        Map<String, Integer> freq = new HashMap<>();
        for (String q : missedQueries) {
            freq.merge(q.trim().toLowerCase(), 1, Integer::sum);
        }

        return freq.entrySet().stream()
                .filter(e -> e.getValue() >= MISS_FREQUENCY_THRESHOLD)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new ContentGap(e.getKey(), e.getValue(), inferCategory(e.getKey())))
                .toList();
    }

    /** 简单推断查询类别 */
    private String inferCategory(String query) {
        if (query.contains("呼吸") || query.contains("放松")) return "coping_tools";
        if (query.contains("朋友") || query.contains("同学")) return "social_skills";
        if (query.contains("考试") || query.contains("成绩")) return "academic_stress";
        if (query.contains("家") || query.contains("爸") || query.contains("妈")) return "family_relations";
        return "general";
    }
}
