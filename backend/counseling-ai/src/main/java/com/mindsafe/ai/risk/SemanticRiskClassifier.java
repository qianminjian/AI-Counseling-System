package com.mindsafe.ai.risk;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * M2 语义风险分类器（RISK-202，design/04 §18.3）
 * <p>
 * 关键词硬规则的儿童场景盲区：小学生高风险表达大量是隐性/隐喻/网络语境
 * （"如果我消失就好了""想睡一辈子""把我的奥特曼卡都送人"），关键词库召回不到。
 * 本分类器用 SAF_001 模板做非流式单次 LLM 调用，输出 L0-L5 分级映射到四色档位。
 * <p>
 * 纪律（§18.3）：
 * 1. 只升不降——调用方只能用本结果升级硬规则档位，不能降级（分类器只返回档位，融合在调用方）；
 * 2. 失败安全——LLM 异常/JSON 解析失败/超时一律返回 null，降级为纯硬规则结果，不阻断对话；
 * 3. 延迟门禁——非流式前置调用共享分诊段 ≤800ms 预算（可配 mindsafe.risk.semantic-timeout-ms），
 *    超时即放弃本轮语义补召（硬规则兜底仍在）。
 * <p>
 * 世界B 分诊段（design/13 §13.2 TriageCall）接线后，本调用并入分诊段单次 JSON，行为定义不变。
 */
@Component
public class SemanticRiskClassifier {

    private static final Logger log = LoggerFactory.getLogger(SemanticRiskClassifier.class);

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final long timeoutMs;

    public SemanticRiskClassifier(ChatClient.Builder chatClientBuilder,
                                  PromptTemplateService promptTemplateService,
                                  @Value("${mindsafe.risk.semantic-timeout-ms:800}") long timeoutMs) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplateService = promptTemplateService;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 语义风险分类（非流式单次调用，超时/失败返回 null）
     *
     * @param message            学生消息（必须已 PII 脱敏——本方法会把内容送入 LLM）
     * @param recentContext      最近对话上下文（可空）
     * @param riskHistorySummary 风险历史摘要（可空）
     * @param gradeLevel         年级（1-6）
     * @return 语义档位（YELLOW/ORANGE/RED）；无风险（L0）或分类失败/超时返回 null
     */
    public RiskLevel classify(String message, String recentContext, String riskHistorySummary, int gradeLevel) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> doClassify(message, recentContext, riskHistorySummary, gradeLevel))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("语义风险分类被中断，降级纯硬规则");
            return null;
        } catch (Exception e) {
            // 失败安全：超时/LLM 异常均降级为纯硬规则结果，不阻断对话
            log.warn("语义风险分类失败/超时（门禁 {}ms），降级纯硬规则: {}", timeoutMs, e.getMessage());
            return null;
        }
    }

    private RiskLevel doClassify(String message, String recentContext, String riskHistorySummary, int gradeLevel) {
        String prompt = promptTemplateService.render(PromptTemplateService.SAF_001, Map.of(
                "current_message", message,
                "recent_context", recentContext != null ? recentContext : "无",
                "risk_history_summary", riskHistorySummary != null ? riskHistorySummary : "无历史记录",
                "grade_level", String.valueOf(gradeLevel)
        ));
        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        return parseRiskLevel(response);
    }

    /** SAF_001 的 L0-L5 分级 → 四色档位（简化解析：提取 "risk_level" 值，解析失败=无风险） */
    static RiskLevel parseRiskLevel(String llmResponse) {
        if (llmResponse == null) return null;
        if (llmResponse.contains("\"L5\"") || llmResponse.contains("\"L4\"")) return RiskLevel.RED;
        if (llmResponse.contains("\"L3\"")) return RiskLevel.ORANGE;
        if (llmResponse.contains("\"L2\"") || llmResponse.contains("\"L1\"")) return RiskLevel.YELLOW;
        return null; // L0 或解析失败 → 无语义风险（硬规则结果兜底）
    }
}
