package com.mindsafe.ai.agent;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Safety Agent（安全监护）— 对齐 design/13 §2.1
 * <p>
 * 双层检测：
 * 1. 快速前置：关键词规则引擎（现有 RiskDetectorService，零延迟）
 * 2. 语义增强：LLM 分析（SAF-001 模板，捕获隐喻/暗示）
 * <p>
 * 降级策略：LLM 失败时回退到纯关键词结果（保证安全底线不丢）。
 */
@Component
public class SafetyAgent implements Agent<SafetyAgent.Input, SafetyAgent.Result> {

    private static final Logger log = LoggerFactory.getLogger(SafetyAgent.class);

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final RiskDetectorService riskDetectorService;

    public SafetyAgent(ChatClient.Builder chatClientBuilder,
                       PromptTemplateService promptTemplateService,
                       RiskDetectorService riskDetectorService) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplateService = promptTemplateService;
        this.riskDetectorService = riskDetectorService;
    }

    @Override
    public String agentName() {
        return "SafetyAgent";
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(30);
    }

    @Override
    public Result execute(Input input, ConversationContext context) {
        // Layer 1: 关键词快速检测（零延迟，不可跳过）
        RiskDetectionResult keywordResult = riskDetectorService.detect(input.message());

        // 关键词命中 RED/ORANGE → 直接返回，不浪费 LLM 调用
        if (keywordResult.isRisky() && keywordResult.level().severity() >= 3) {
            log.warn("SafetyAgent 关键词命中高风险: level={}, category={}",
                    keywordResult.level(), keywordResult.category());
            return new Result(
                    keywordResult.level(),
                    keywordResult.category(),
                    keywordResult.matchedKeywords(),
                    true,
                    keywordResult.level() == RiskLevel.RED,
                    keywordResult.suggestion(),
                    "keyword"
            );
        }

        // Layer 2: LLM 语义分析（捕获隐喻、暗示、上下文组合风险）
        try {
            String prompt = promptTemplateService.render(PromptTemplateService.SAF_001, Map.of(
                    "current_message", input.message(),
                    "recent_context", input.recentContext() != null ? input.recentContext() : "无",
                    "risk_history_summary", input.riskHistorySummary() != null ? input.riskHistorySummary() : "无历史记录",
                    "grade_level", String.valueOf(context.gradeLevel())
            ));

            String llmResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // 解析 LLM JSON 响应（简化解析，提取 risk_level）
            RiskLevel llmLevel = parseRiskLevel(llmResponse);
            if (llmLevel != null && llmLevel.severity() > (keywordResult.isRisky() ? keywordResult.level().severity() : 0)) {
                log.info("SafetyAgent LLM 提升风险等级: keyword={}, llm={}", keywordResult.level(), llmLevel);
                return new Result(llmLevel, "llm_semantic", List.of(), false,
                        llmLevel.severity() >= 4, "LLM 语义分析检测到风险", "llm");
            }
        } catch (Exception e) {
            log.warn("SafetyAgent LLM 调用失败，回退关键词结果: {}", e.getMessage());
        }

        // 返回关键词结果（可能是无风险）
        if (keywordResult.isRisky()) {
            return new Result(keywordResult.level(), keywordResult.category(),
                    keywordResult.matchedKeywords(), false, false,
                    keywordResult.suggestion(), "keyword");
        }
        return Result.safe();
    }

    @Override
    public Result fallback(Input input, ConversationContext context, Throwable cause) {
        log.error("SafetyAgent 降级到纯关键词模式: {}", cause.getMessage());
        RiskDetectionResult keywordResult = riskDetectorService.detect(input.message());
        if (keywordResult.isRisky()) {
            return new Result(keywordResult.level(), keywordResult.category(),
                    keywordResult.matchedKeywords(), true,
                    keywordResult.level() == RiskLevel.RED,
                    keywordResult.suggestion(), "keyword_fallback");
        }
        return Result.safe();
    }

    private RiskLevel parseRiskLevel(String llmResponse) {
        if (llmResponse == null) return null;
        // 简单提取 "risk_level": "L4" 模式
        if (llmResponse.contains("\"L5\"")) return RiskLevel.RED;
        if (llmResponse.contains("\"L4\"")) return RiskLevel.RED;
        if (llmResponse.contains("\"L3\"")) return RiskLevel.ORANGE;
        if (llmResponse.contains("\"L2\"")) return RiskLevel.YELLOW;
        if (llmResponse.contains("\"L1\"")) return RiskLevel.YELLOW;
        return null; // L0 或解析失败 → 无风险
    }

    // ===== 输入/输出类型 =====

    public record Input(String message, String recentContext, String riskHistorySummary) {}

    public record Result(
            RiskLevel riskLevel,
            String category,
            List<String> matchedKeywords,
            boolean needsHumanReview,
            boolean needsImmediateEscalation,
            String suggestion,
            String detectionSource
    ) {
        public boolean isRisky() {
            return riskLevel != null;
        }

        public static Result safe() {
            return new Result(null, "normal", List.of(), false, false, null, "none");
        }
    }
}
