package com.mindsafe.service.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索 RRF 与内容缺口识别（KB-103，design/49 P2/P3）
 * <p>
 * <ul>
 *   <li>混合检索：向量(0.6) + 关键词(0.4) → RRF 融合排序</li>
 *   <li>未命中查询日志：高频未命中 → 内容缺口清单（EditorialWorkflowService 运营报表消费）</li>
 * </ul>
 * 纯函数实现。
 * <p>
 * BA-05（DOC-074）：groundedness 评估已删除——调用点把「请求条数/返回条数」当「检索数/引用数」，
 * score 恒 {0,0.33,0.67,1} 伪信号且无看板/metrics 消费者；真计算需回复文本引用分析（无消费方，YAGNI）。
 */
@Component
public class HybridRetrievalService {

    /** 向量检索权重 */
    public static final double VECTOR_WEIGHT = 0.6;

    /** 关键词检索权重 */
    public static final double KEYWORD_WEIGHT = 0.4;

    /** RRF 常数 k（标准值 60） */
    public static final int RRF_K = 60;

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
