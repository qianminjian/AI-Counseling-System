package com.mindsafe.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.ai.risk.RiskScoreCalculator;
import com.mindsafe.ai.risk.SemanticRiskClassifier;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 对话风险处理器（P0-2 审计修复：从 ConversationServiceImpl 上帝类拆分）。
 * <p>
 * 职责：
 * <ul>
 *   <li>文本关键词硬规则检测（RISK-101）</li>
 *   <li>语义风险分类融合（RISK-202，design/04 §18.3）</li>
 *   <li>多信号融合（文本 + 语音情绪，design/04 §18.1）</li>
 *   <li>风险事件持久化 + 结构化评分 + 教师通知（RISK-203）</li>
 * </ul>
 */
@Service
public class ConversationRiskProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConversationRiskProcessor.class);

    private final RiskDetectorService riskDetectorService;
    private final SemanticRiskClassifier semanticRiskClassifier;
    private final RiskScoreCalculator riskScoreCalculator;
    private final RiskEventMapper riskEventMapper;
    private final NotificationService notificationService;
    private final RiskNotifyOutboxService riskNotifyOutboxService;
    private final ObjectMapper objectMapper;

    public ConversationRiskProcessor(RiskDetectorService riskDetectorService,
                                     SemanticRiskClassifier semanticRiskClassifier,
                                     RiskScoreCalculator riskScoreCalculator,
                                     RiskEventMapper riskEventMapper,
                                     NotificationService notificationService,
                                     RiskNotifyOutboxService riskNotifyOutboxService,
                                     ObjectMapper objectMapper) {
        this.riskDetectorService = riskDetectorService;
        this.semanticRiskClassifier = semanticRiskClassifier;
        this.riskScoreCalculator = riskScoreCalculator;
        this.riskEventMapper = riskEventMapper;
        this.notificationService = notificationService;
        this.riskNotifyOutboxService = riskNotifyOutboxService;
        this.objectMapper = objectMapper;
    }

    /**
     * 文本关键词硬规则检测（用原文——需捕获"地址+自伤"等组合）。
     */
    public RiskDetectionResult detectKeywordRisk(String content) {
        return riskDetectorService.detect(content);
    }

    /**
     * RISK-202：M2 语义风险分类融合（design/04 §18.3）
     * <p>
     * 分工铁律：硬规则管"宁多报"（RED/ORANGE 已接住，不再调语义层，更不可降级）；
     * 语义层管"隐性补召"（隐喻/告别行为/送东西/网络黑话），只能升级风险档位。
     */
    public RiskDetectionResult applySemanticRisk(RiskDetectionResult keywordResult,
                                                 String safeContent, int grade) {
        // 硬规则已达橙/红 → 语义层不参与（省 LLM 调用，且语义层无权降级，§九铁律）
        if (keywordResult.level().severity() >= RiskLevel.ORANGE.severity()) {
            return keywordResult;
        }
        RiskLevel semanticLevel = semanticRiskClassifier.classify(safeContent, null, null, grade);
        if (semanticLevel == null || semanticLevel.severity() <= keywordResult.level().severity()) {
            return keywordResult; // 无语义风险或未超过关键词档位（只升不降）
        }
        log.warn("语义分类升级风险等级: keyword={}, semantic={}", keywordResult.level(), semanticLevel);
        int score = semanticLevel == RiskLevel.RED ? 85 : semanticLevel == RiskLevel.ORANGE ? 60 : 40;
        return new RiskDetectionResult(semanticLevel, "llm_semantic", List.of(), score, false,
                "语义分析识别到隐性风险表达（隐喻/暗示），请结合原文人工复核");
    }

    /**
     * 多信号融合风险判断
     * <p>
     * 规则：
     * 1. 文本命中红色关键词 → 直接 RED（不可降级）
     * 2. 文本命中橙色 + 语音消极 → 升级 RED
     * 3. 文本命中橙色（无语音） → ORANGE
     * 4. 文本命中黄色 + 语音消极 → 升级 ORANGE
     * 5. 连续 3 次消极语音情绪（无文本风险） → YELLOW（情绪趋势预警）
     * 6. 单次消极语音（无文本风险） → 不触发风险事件（仅记录）
     */
    public RiskLevel fuseRiskSignals(RiskDetectionResult textRisk, String voiceEmotion,
                                     Double voiceConfidence, int consecutiveNegativeCount) {
        boolean hasNegativeVoice = voiceEmotion != null && voiceConfidence != null
                && voiceConfidence > 0.6
                && isNegativeEmotion(voiceEmotion);

        // 规则 1：文本红色不可降级
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.RED) {
            return RiskLevel.RED;
        }

        // 规则 2：文本橙色 + 语音消极 → 升级红色
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.ORANGE && hasNegativeVoice) {
            return RiskLevel.RED;
        }

        // 规则 3：文本橙色（无语音加成）
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.ORANGE) {
            return RiskLevel.ORANGE;
        }

        // 规则 4：文本黄色 + 语音消极 → 升级橙色
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.YELLOW && hasNegativeVoice) {
            return RiskLevel.ORANGE;
        }

        // 规则 4b：文本黄色（无语音加成）
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.YELLOW) {
            return RiskLevel.YELLOW;
        }

        // 规则 5：连续 3 次消极语音（无文本风险）→ 情绪趋势预警
        if (!textRisk.isRisky() && consecutiveNegativeCount >= 3) {
            return RiskLevel.YELLOW;
        }

        // 规则 6：单次消极语音不触发风险事件
        return null;
    }

    /**
     * 持久化风险事件 + 结构化评分（RISK-203）+ 教师通知（P0-4 outbox 补偿）。
     * <p>
     * fail-fast：DB 写入失败必须上抛（安全关键记录不允许静默丢失，
     * 由调用方决定降级策略）；教师通知失败标记 notify_status=failed，
     * 由补偿任务重试（超 5 次/24h 转 dead 人工兜底），不再静默丢失。
     */
    public void persistRiskEvent(SessionState session, RiskDetectionResult riskResult) {
        RiskEvent event;
        try {
            event = RiskEvent.fromDetection(
                    session.getTenantId(),
                    session.getStudentUserId(),
                    session.getSessionId(),
                    riskResult.category(),
                    riskResult.level().severity()
            );

            // RISK-203：结构化风险评分（C-SSRS 儿童适配，可解释 reason_codes 供教师复核）
            // P1 修复：从命中关键词抽取意图/计划/C-SSRS 因子，不再恒 0（审计 7/11 维度恒 0）
            ScoreFactors factors = extractScoreFactors(riskResult.matchedKeywords());
            RiskScoreCalculator.ScoreInput scoreInput = new RiskScoreCalculator.ScoreInput(
                    riskResult.score(),           // categoryBaseScore
                    factors.intentWeight(),       // intentWeight（明确+15/含混+8）
                    factors.planWeight(),         // planWeight（方法类每+5 上限 20）
                    10,                           // recencyWeight（当前会话=今天）
                    0,                            // actionWeight
                    0,                            // repetitionWeight
                    0,                            // protectiveWeight
                    0,                            // falsePositivePenalty
                    0.8,                          // confidenceAdjustment（硬规则默认 0.8）
                    riskResult.level().severity() >= RiskLevel.RED.severity() ? riskResult.level() : null,
                    factors.cssrsIdeation(),      // C-SSRS 意念强度轴（被动抽取）
                    factors.cssrsBehavior()       // C-SSRS 行为轴（被动抽取）
            );
            RiskScoreCalculator.ScoreResult scoreResult = riskScoreCalculator.calculate(scoreInput);
            // A2（2026-08-05）：结构化评分随事件落库（risk_score + reason_codes），
            // 教师端可排序/复核，不再只打日志（原实现计算了不存储，画像/教师端不可见）
            event.setRiskScore(scoreResult.score());
            event.setReasonCodes(objectMapper.writeValueAsString(scoreResult.reasonCodes()));
            log.info("风险评分计算: sessionId={}, score={}, level={}, reasons={}",
                    session.getSessionId(), scoreResult.score(), scoreResult.level(), scoreResult.reasonCodes());

            riskEventMapper.insert(event);
            log.info("风险事件已持久化: riskEventId={}, level={}, score={}",
                    event.getRiskEventId(), riskResult.level(), scoreResult.score());
        } catch (Exception e) {
            log.error("风险事件持久化失败(fail-fast 上抛): sessionId={}, level={}",
                    session.getSessionId(), riskResult.level(), e);
            throw new IllegalStateException("风险事件持久化失败", e);
        }

        // 教师通知 + outbox 状态标记（P0-4）：失败不再静默，进补偿队列
        try {
            notificationService.notifyRiskEvent(event);
            riskNotifyOutboxService.markSent(event);
        } catch (Exception e) {
            log.error("风险教师通知失败(已标记 failed 进补偿队列): riskEventId={}", event.getRiskEventId(), e);
            riskNotifyOutboxService.markFailed(event);
        }
    }

    /**
     * RISK-203 评分因子抽取结果。
     *
     * @param intentWeight  意图权重（明确+15/含混+8/无 0）
     * @param planWeight    计划权重（方法类关键词每类+5，上限 20）
     * @param cssrsIdeation C-SSRS 意念强度轴（null=非自伤类）
     * @param cssrsBehavior C-SSRS 行为轴（null=无行为证据）
     */
    public record ScoreFactors(
            int intentWeight,
            int planWeight,
            RiskScoreCalculator.CssrsIdeation cssrsIdeation,
            RiskScoreCalculator.CssrsBehavior cssrsBehavior
    ) {
        public static ScoreFactors zero() {
            return new ScoreFactors(0, 0, null, null);
        }
    }

    // ===== 评分因子关键词映射（design/04 §十权重表 + 现有风险词典） =====

    /** 明确自伤意图（I=+15）："我不想活了" 等直接表达 */
    private static final Set<String> EXPLICIT_INTENT_KEYWORDS = Set.of(
            "想死", "自杀", "去死", "杀了自己", "结束生命", "不想活", "不想活了", "死了算了"
    );

    /** 含混死亡愿望（I=+8）："活着没意思" 等间接表达 */
    private static final Set<String> VAGUE_INTENT_KEYWORDS = Set.of(
            "活着没意思", "活着很累", "没希望", "什么都没意思", "我是累赘", "把东西送人", "告别"
    );

    /** 自伤方法/工具关键词（P 每类 +5，上限 20） */
    private static final Set<String> SELF_HARM_METHOD_KEYWORDS = Set.of(
            "跳楼", "上吊", "割腕", "吃药", "带刀"
    );

    /** 准备行为关键词（C-SSRS 行为轴 PREPARATORY） */
    private static final Set<String> PREPARATORY_KEYWORDS = Set.of("遗书");

    /**
     * RISK-203 审计修复：从命中的风险关键词抽取评分因子，替代恒 0/恒 null。
     * <p>
     * 纯函数、可解释：命中词来自硬规则词典（RiskDetectorServiceImpl），
     * 语义层路径（matchedKeywords 为空）返回全零，不越权加分。
     */
    public static ScoreFactors extractScoreFactors(List<String> matchedKeywords) {
        if (matchedKeywords == null || matchedKeywords.isEmpty()) {
            return ScoreFactors.zero();
        }

        // 意图权重：明确 > 含混（只取最高一档，不叠加）
        boolean explicitIntent = containsAny(matchedKeywords, EXPLICIT_INTENT_KEYWORDS);
        boolean vagueIntent = containsAny(matchedKeywords, VAGUE_INTENT_KEYWORDS);
        int intentWeight = explicitIntent ? 15 : vagueIntent ? 8 : 0;

        // 计划权重：方法类关键词每个 +5，上限 20
        int planWeight = matchedKeywords.stream()
                .filter(SELF_HARM_METHOD_KEYWORDS::contains)
                .limit(4) // 5*4=20 封顶
                .mapToInt(kw -> 5)
                .sum();

        // C-SSRS 意念强度轴：取最高档（计划 > 方法 > 主动 > 死亡愿望）
        RiskScoreCalculator.CssrsIdeation ideation = null;
        if (containsAny(matchedKeywords, PREPARATORY_KEYWORDS)) {
            ideation = RiskScoreCalculator.CssrsIdeation.WITH_PLAN_INTENT;
        } else if (containsAny(matchedKeywords, SELF_HARM_METHOD_KEYWORDS)) {
            ideation = RiskScoreCalculator.CssrsIdeation.WITH_METHOD;
        } else if (explicitIntent) {
            ideation = RiskScoreCalculator.CssrsIdeation.ACTIVE_IDEATION;
        } else if (vagueIntent) {
            ideation = RiskScoreCalculator.CssrsIdeation.DEATH_WISH;
        }

        // C-SSRS 行为轴：准备行为（写遗书）
        RiskScoreCalculator.CssrsBehavior behavior = containsAny(matchedKeywords, PREPARATORY_KEYWORDS)
                ? RiskScoreCalculator.CssrsBehavior.PREPARATORY
                : null;

        return new ScoreFactors(intentWeight, planWeight, ideation, behavior);
    }

    private static boolean containsAny(List<String> keywords, Set<String> candidates) {
        return keywords.stream().anyMatch(candidates::contains);
    }

    /**
     * 构建语音情绪风险建议文案。
     */
    public String buildEmotionSuggestion(String voiceEmotion) {
        return switch (voiceEmotion) {
            case "sad" -> "学生语音情绪持续低落，建议关注";
            case "fearful" -> "学生语音中检测到恐惧情绪，建议关注";
            case "angry" -> "学生语音情绪激动，建议关注";
            default -> "学生语音情绪异常，建议关注";
        };
    }

    private boolean isNegativeEmotion(String emotion) {
        return switch (emotion) {
            case "sad", "fearful", "angry", "disgusted" -> true;
            default -> false;
        };
    }
}
