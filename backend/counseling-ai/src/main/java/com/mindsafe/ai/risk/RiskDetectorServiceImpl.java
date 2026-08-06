package com.mindsafe.ai.risk;

import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 风险识别服务实现（M1 关键词硬规则版本）
 * <p>
 * 对齐 design/04 风险识别规则库：
 * - 10 类风险信号
 * - 强信号关键词快速召回
 * - 强制升级条件（红色硬规则）
 * - 基础否定/误报控制
 * <p>
 * ARCH-003（2026-08-06）：风险词典已收敛至 {@link RiskKeywordRegistry}（单一规则源），
 * 本类只保留判定逻辑，不再内嵌词表。
 * <p>
 * M2+ 迭代方向：语义分类、上下文评分、LLM 辅助判断、趋势跟踪。
 */
@Service
public class RiskDetectorServiceImpl implements RiskDetectorService {

    private static final Logger log = LoggerFactory.getLogger(RiskDetectorServiceImpl.class);

    @Override
    public RiskDetectionResult detect(String message) {
        if (message == null || message.isBlank()) {
            return RiskDetectionResult.safe();
        }

        String normalized = message.toLowerCase().trim();

        // 1. 检查引用/假设语境
        boolean hasContext = RiskKeywordRegistry.CONTEXT_PATTERN.matcher(normalized).find();

        // 2. 红色硬规则检测（不可被引用语境降级，但排除直接否定前缀）
        List<String> redMatches = matchKeywords(normalized, RiskKeywordRegistry.RED_HARD);
        if (!redMatches.isEmpty()) {
            log.warn("🚨 红色硬规则命中: keywords={}", redMatches);
            return new RiskDetectionResult(
                    RiskLevel.RED,
                    RiskKeywordRegistry.findCategory(redMatches),
                    redMatches,
                    RiskKeywordRegistry.SCORE_HARD,
                    true,
                    "立即中断普通对话，进入安全响应，通知心理老师"
            );
        }

        // 3. 橙色关键词检测
        List<String> orangeMatches = matchKeywords(normalized, RiskKeywordRegistry.ORANGE);
        if (!orangeMatches.isEmpty()) {
            // 检查每个命中关键词是否有否定前缀
            boolean allNegated = orangeMatches.stream().allMatch(kw -> hasNegationPrefix(normalized, kw));
            if ((allNegated || hasContext) && !isSensitiveCategory(orangeMatches)) {
                log.info("橙色关键词命中但含否定/引用语境，降为黄色: keywords={}", orangeMatches);
                return new RiskDetectionResult(
                        RiskLevel.YELLOW,
                        RiskKeywordRegistry.findCategory(orangeMatches),
                        orangeMatches,
                        RiskKeywordRegistry.SCORE_YELLOW,
                        false,
                        "标记关注，允许继续对话，生成摘要给心理老师"
                );
            }
            log.warn("⚠️ 橙色风险命中: keywords={}", orangeMatches);
            return new RiskDetectionResult(
                    RiskLevel.ORANGE,
                    RiskKeywordRegistry.findCategory(orangeMatches),
                    orangeMatches,
                    RiskKeywordRegistry.SCORE_ORANGE,
                    false,
                    "转人工队列，AI 只做稳定和求助引导"
            );
        }

        // 4. 黄色关键词检测
        List<String> yellowMatches = matchKeywords(normalized, RiskKeywordRegistry.YELLOW);
        if (!yellowMatches.isEmpty()) {
            boolean allNegated = yellowMatches.stream().allMatch(kw -> hasNegationPrefix(normalized, kw));
            if (allNegated || hasContext) {
                return RiskDetectionResult.safe();
            }
            log.debug("黄色风险命中: keywords={}", yellowMatches);
            return new RiskDetectionResult(
                    RiskLevel.YELLOW,
                    RiskKeywordRegistry.findCategory(yellowMatches),
                    yellowMatches,
                    RiskKeywordRegistry.SCORE_ORANGE_MIN,
                    false,
                    "允许继续 CBT 微干预，趋势观察"
            );
        }

        // 5. 无风险
        return RiskDetectionResult.safe();
    }

    /** 检查关键词前是否有否定词（支持「不想死」这类否定词与关键词重叠的情况） */
    private boolean hasNegationPrefix(String text, String keyword) {
        int idx = text.indexOf(keyword);
        if (idx <= 0) return false;
        // 取关键词前 6 个字符
        String before = text.substring(Math.max(0, idx - 6), idx);
        // 检查 before 是否以否定词结尾（如「我没有」+「离家出走」）
        if (RiskKeywordRegistry.NEGATION_WORDS.stream().anyMatch(before::endsWith)) return true;
        // 检查否定词是否与关键词开头重叠（如「不想」+「想死」，「不」在 before 中，「想」在 keyword 中）
        for (String neg : RiskKeywordRegistry.NEGATION_WORDS) {
            for (int i = 1; i < neg.length(); i++) {
                if (before.endsWith(neg.substring(0, i)) && keyword.startsWith(neg.substring(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 关键词匹配（design/04 §九铁律：RED 不可被否定/引用降级，仅允许人工核验回收误报）。
     * 词典来源：{@link RiskKeywordRegistry}（ARCH-003 单一规则源）。
     */
    private List<String> matchKeywords(String text, Set<String> keywords) {
        List<String> matches = new ArrayList<>();
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                // 设计铁律："我不想死""我不会自杀"等否定表达本身即高风险信号，
                // 命中即 RED；否定降噪仅适用于橙/黄档，误报由教师人工核验回收
                matches.add(keyword);
            }
        }
        return matches;
    }

    /** 判断是否为不可降级的敏感类别（性侵/虐待） */
    private boolean isSensitiveCategory(List<String> matchedKeywords) {
        String category = RiskKeywordRegistry.findCategory(matchedKeywords);
        return category.contains("性侵") || category.contains("虐待");
    }
}
