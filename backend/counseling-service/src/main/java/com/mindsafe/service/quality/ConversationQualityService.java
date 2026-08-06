package com.mindsafe.service.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 对话质量评估服务（AI-001 指标体系 + AI-002 LLM-as-Judge 管线）
 * <p>
 * 会话结束后异步调用：使用 LLM 对会话进行四维评分（共情度/CBT完成度/安全合规/互动投入度），
 * 低分自动标记供教师端抽检。
 * <ul>
 *   <li>empathy_score：AI 是否准确识别并回应学生情绪</li>
 *   <li>cbt_completion：是否推进了 CBT 流程</li>
 *   <li>safety_compliance：是否遵守安全规范</li>
 *   <li>engagement_score：学生参与程度与对话深度</li>
 * </ul>
 * 失败静默降级，不影响主流程。
 */
@Service
public class ConversationQualityService {

    private static final Logger log = LoggerFactory.getLogger(ConversationQualityService.class);

    /** 低分阈值：综合分低于此值自动标记 */
    private static final double FLAG_THRESHOLD = 0.4;

    /** 抽样率：仅评估部分会话（0.0~1.0），降低 LLM 调用成本 */
    private static final double SAMPLE_RATE = 0.3;

    private final AiChatService aiChatService;
    private final QualityScoreMapper qualityScoreMapper;
    private final CounselingSessionMapper sessionMapper;
    private final EmpathyStructureEvaluator empathyStructureEvaluator;
    // ARCH-010 P2-2：注入唯一 ObjectMapper（此前 new，配置不统一）
    private final ObjectMapper objectMapper;
    // ARCH-010 P2-5：评估失败 metrics（失败率告警依据）
    private final Counter evaluationFailureCounter;

    public ConversationQualityService(AiChatService aiChatService,
                                      QualityScoreMapper qualityScoreMapper,
                                      CounselingSessionMapper sessionMapper,
                                      EmpathyStructureEvaluator empathyStructureEvaluator,
                                      ObjectMapper objectMapper,
                                      MeterRegistry meterRegistry) {
        this.aiChatService = aiChatService;
        this.qualityScoreMapper = qualityScoreMapper;
        this.sessionMapper = sessionMapper;
        this.empathyStructureEvaluator = empathyStructureEvaluator;
        this.objectMapper = objectMapper;
        this.evaluationFailureCounter = Counter.builder("mindsafe.pipeline.failure")
                .tag("stage", "evaluation")
                .register(meterRegistry);
    }

    /**
     * 异步评估单个会话质量（会话结束后调用）
     * 按抽样率决定是否评估，降低 LLM 成本
     */
    @Async
    public void evaluateSessionAsync(UUID tenantId, UUID sessionId, String conversationText) {
        try {
            // 抽样决策（独立方法便于测试覆写，默认 Math.random 不可控）
            if (!shouldEvaluate()) {
                log.debug("质量评估抽样跳过: sessionId={}", sessionId);
                return;
            }
            evaluateSession(tenantId, sessionId, conversationText);
        } catch (Exception e) {
            evaluationFailureCounter.increment();
            log.warn("质量评估失败（不影响主流程）: sessionId={}, stage=evaluation", sessionId, e);
        }
    }

    /** 抽样判定：按抽样率决定是否评估（包级可见，测试子类覆写强制选中） */
    boolean shouldEvaluate() {
        return Math.random() <= SAMPLE_RATE;
    }

    /**
     * 同步评估单个会话（供教师端手动触发或定时任务使用）
     */
    public QualityScore evaluateSession(UUID tenantId, UUID sessionId, String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }

        // 幂等：已评估则跳过
        QualityScore existing = qualityScoreMapper.selectOne(
                new LambdaQueryWrapper<QualityScore>()
                        .eq(QualityScore::getTenantId, tenantId)
                        .eq(QualityScore::getSessionId, sessionId)
        );
        if (existing != null) {
            return existing;
        }

        // LLM-as-Judge 评分
        String judgeResult = aiChatService.evaluateConversationQuality(conversationText);
        if (judgeResult == null || judgeResult.isBlank()) {
            return null;
        }

        // 解析评分
        QualityScore score = parseJudgeResult(tenantId, sessionId, judgeResult);
        if (score == null) {
            return null;
        }

        // 低分标记
        if (score.getOverallScore() != null && score.getOverallScore().doubleValue() < FLAG_THRESHOLD) {
            score.setFlagged(true);
            score.setFlagReason("综合分 " + score.getOverallScore() + " 低于阈值 " + FLAG_THRESHOLD);
        } else {
            score.setFlagged(false);
        }

        score.setEvaluator("llm-judge");
        score.setRawResponse(judgeResult);
        score.setEvaluatedAt(Instant.now());

        // EMP-201：确定性共情结构评估（LLM-as-Judge 的确定性补充，design/52 §四）
        supplementEmpathyStructure(score, conversationText);

        qualityScoreMapper.insert(score);

        log.info("质量评估完成: sessionId={}, overall={}, flagged={}",
                sessionId, score.getOverallScore(), score.getFlagged());
        return score;
    }

    private QualityScore parseJudgeResult(UUID tenantId, UUID sessionId, String raw) {
        try {
            String json = stripCodeFence(raw);
            JsonNode node = objectMapper.readTree(json);

            QualityScore score = new QualityScore();
            score.setScoreId(UUID.randomUUID());
            score.setTenantId(tenantId);
            score.setSessionId(sessionId);

            BigDecimal empathy = readScore(node, "empathy_score");
            BigDecimal cbt = readScore(node, "cbt_completion");
            BigDecimal safety = readScore(node, "safety_compliance");
            BigDecimal engagement = readScore(node, "engagement_score");

            score.setEmpathyScore(empathy);
            score.setCbtCompletion(cbt);
            score.setSafetyCompliance(safety);
            score.setEngagementScore(engagement);

            // 加权综合分：安全合规权重最高（0.35），共情（0.25），CBT（0.2），投入度（0.2）
            double overall = 0;
            int weightSum = 0;
            if (empathy != null) { overall += empathy.doubleValue() * 0.25; weightSum += 25; }
            if (cbt != null) { overall += cbt.doubleValue() * 0.20; weightSum += 20; }
            if (safety != null) { overall += safety.doubleValue() * 0.35; weightSum += 35; }
            if (engagement != null) { overall += engagement.doubleValue() * 0.20; weightSum += 20; }

            if (weightSum > 0) {
                score.setOverallScore(BigDecimal.valueOf(overall / (weightSum / 100.0))
                        .setScale(3, RoundingMode.HALF_UP));
            }

            return score;
        } catch (Exception e) {
            log.warn("质量评估结果解析失败: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal readScore(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val != null && val.isNumber()) {
            double d = val.asDouble();
            if (d >= 0 && d <= 1) {
                return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    private String stripCodeFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    /**
     * EMP-201：确定性共情结构评估（LLM-as-Judge empathy_score 的规则化补充）。
     * <p>
     * 从对话文本提取 AI 回复，运行「命名-确认-容纳」三段式检测 + 反模式检测。
     * 结果记录日志，低分且 LLM 给高分时记录差异（供后续校准）。
     */
    private void supplementEmpathyStructure(QualityScore score, String conversationText) {
        try {
            // 简化提取 AI 回复（按“波波”/“AI”/“助手”前缀分割）
            List<String> aiResponses = java.util.Arrays.stream(conversationText.split("\n"))
                    .filter(line -> line.startsWith("波波") || line.startsWith("AI") || line.startsWith("助手"))
                    .toList();
            if (aiResponses.isEmpty()) return;

            EmpathyStructureEvaluator.SessionEmpathySummary summary =
                    empathyStructureEvaluator.summarizeSession(aiResponses);

            log.info("EMP-201 共情结构评估: sessionId={}, turns={}, avgScore={}, effectiveRatio={}, antiPatternTurns={}",
                    score.getSessionId(), summary.turnsEvaluated(),
                    summary.avgStructureScore(), summary.effectiveRatio(), summary.antiPatternTurns());

            // 交叉校验：LLM 共情分高但规则检测低 → 记录差异（供后续校准）
            if (score.getEmpathyScore() != null
                    && score.getEmpathyScore().doubleValue() > 0.7
                    && summary.avgStructureScore() < 0.3) {
                log.warn("EMP-201 共情评分差异: sessionId={}, llm={}, rule={}",
                        score.getSessionId(), score.getEmpathyScore(), summary.avgStructureScore());
            }
        } catch (Exception e) {
            log.debug("EMP-201 共情结构评估失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
