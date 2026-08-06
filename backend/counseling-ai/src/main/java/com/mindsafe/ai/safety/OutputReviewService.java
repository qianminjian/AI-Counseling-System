package com.mindsafe.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Layer2：AI 输出异步 LLM 语义审查（SAF-002）。
 * <p>
 * 在流式输出完成后，对 AI 完整回复做非流式 SAF-002 审查，检测 Layer1 关键词
 * 无法覆盖的<b>细微违规</b>（适龄性、隐性诊断、语气、依赖诱导的隐晦表达等）。
 * <ul>
 *   <li>fire-and-forget：专用线程池异步执行，绝不阻塞主流（对用户零延迟）；</li>
 *   <li>四决策处置（SAFE-202，design/14 §12.2）：
 *     pass→放行；rewrite→用 LLM 改写版召回替换（YELLOW 留痕）；
 *     block→用预审核话术召回替换（ORANGE 留痕+通知）；
 *     escalate→用预审核安全处置话术召回替换（RED 留痕+通知）；</li>
 *   <li>召回=替换已落记忆的回复（SSE 单向流无实时召回通道，下一轮历史即替换后版本）+ risk_events 审计链；
 *     召回话术预审核（{@link RecallPhrases}），不由 LLM 现场生成（rewrite 档除外，仅限适龄性轻微违规）。</li>
 * </ul>
 */
@Component
public class OutputReviewService {

    private static final Logger log = LoggerFactory.getLogger(OutputReviewService.class);

    private static final String TEMPLATE_PATH = "/prompts/safety/safety_output_guard_zh-CN_v1.0.0.md";
    private static final String DECISION_PASS = "pass";
    private static final String DECISION_REWRITE = "rewrite";
    private static final String DECISION_BLOCK = "block";
    private static final String DECISION_ESCALATE = "escalate";

    /** SAF-002 审查结果（四决策 + 改写版/升级理由） */
    record ReviewOutcome(String decision, String rewrittenReply, String escalationReason) {
    }

    private final ChatClient reviewClient;
    private final Executor outputReviewExecutor;
    private final OutputSafetyReporter reporter;
    // ARCH-010 P2-2：注入唯一 ObjectMapper（此前 new，配置不统一）
    private final ObjectMapper objectMapper;

    private String promptTemplate = "";

    public OutputReviewService(ChatClient.Builder chatClientBuilder,
                               @Qualifier("outputReviewExecutor") Executor outputReviewExecutor,
                               OutputSafetyReporter reporter,
                               ObjectMapper objectMapper) {
        // 独立 ChatClient 实例：审查调用与主对话互不干扰
        this.reviewClient = chatClientBuilder.build();
        this.outputReviewExecutor = outputReviewExecutor;
        this.reporter = reporter;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadTemplate() {
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE_PATH)) {
            if (in == null) {
                log.error("SAF-002 审查模板不存在: {}，Layer2 审查将不生效", TEMPLATE_PATH);
                return;
            }
            promptTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("SAF-002 输出审查模板加载完成: {} 字符", promptTemplate.length());
        } catch (Exception e) {
            log.error("SAF-002 审查模板加载失败: {}", TEMPLATE_PATH, e);
        }
    }

    /**
     * 异步审查 AI 完整回复（fire-and-forget，绝不阻塞/影响主流）。
     *
     * @param sessionId  会话 ID
     * @param fullReply  AI 本轮完整回复文本
     * @param emotionTag 本轮情绪标签（提供上下文）
     */
    public void reviewAsync(UUID sessionId, String fullReply, String emotionTag) {
        if (promptTemplate.isBlank() || fullReply == null || fullReply.isBlank()) {
            return;
        }
        // A1（2026-08-05）：outputReviewExecutor 为手动线程池，不走 TaskDecorator——
        // 提交时捕获调用线程的租户上下文/系统作用域，子线程执行前恢复、finally 清除；
        // 否则 OutputSafetyReporterImpl 的 DB 写入（sessionMapper/risk_events）在无上下文下
        // 触发租户行隔离 fail-fast，被异常兜底吞掉 → Layer2 留痕与 SAFE-202 召回静默失效。
        UUID tenantId = TenantContextHolder.get();
        boolean systemScope = TenantContextHolder.isSystemScope();
        CompletableFuture.runAsync(() -> {
            try {
                if (tenantId != null) {
                    TenantContextHolder.set(tenantId);
                }
                TenantContextHolder.setSystemScope(systemScope);
                review(sessionId, fullReply, emotionTag);
            } finally {
                TenantContextHolder.clear();
            }
        }, outputReviewExecutor)
                .exceptionally(e -> {
                    log.error("Layer2 异步审查任务异常: sessionId={}", sessionId, e);
                    return null;
                });
    }

    /** 同步审查逻辑（仅异步线程内调用） */
    void review(UUID sessionId, String fullReply, String emotionTag) {
        try {
            String prompt = promptTemplate
                    .replace("{candidate_reply}", fullReply)
                    .replace("{context}", "学生情绪标签：" + emotionTag);

            String raw = reviewClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) {
                log.warn("Layer2 审查返回空结果: sessionId={}", sessionId);
                return;
            }

            ReviewOutcome outcome = parseReview(raw);
            if (outcome == null || outcome.decision() == null) {
                return; // 解析失败视为未决，不上报
            }
            String decision = outcome.decision();
            if (DECISION_PASS.equals(decision)) {
                log.debug("Layer2 审查通过: sessionId={}", sessionId);
                return;
            }

            log.warn("⚠️ Layer2 审查违规: sessionId={}, decision={}", sessionId, decision);
            try {
                dispatch(sessionId, decision, outcome, raw);
            } catch (Exception e) {
                log.error("Layer2 违规处置失败: sessionId={}", sessionId, e);
            }
        } catch (Exception e) {
            // 审查失败仅记录，绝不影响主流程
            log.error("Layer2 审查执行失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 四决策处置分发（SAFE-202）。
     * <ul>
     *   <li>rewrite：适龄性轻微违规 → 用 LLM 改写版召回替换；改写版缺失时退化为仅留痕；</li>
     *   <li>block：1-6 项违规 → 预审核话术召回替换 + 通知教师；</li>
     *   <li>escalate：高风险未处置 → 预审核安全处置话术召回替换 + 通知教师；</li>
     *   <li>未知决策：兜底仅留痕。</li>
     * </ul>
     */
    private void dispatch(UUID sessionId, String decision, ReviewOutcome outcome, String raw) {
        switch (decision) {
            case DECISION_REWRITE -> {
                String rewritten = outcome.rewrittenReply();
                if (rewritten == null || rewritten.isBlank()) {
                    log.warn("rewrite 决策但缺少改写版，退化为仅留痕: sessionId={}", sessionId);
                    reporter.reportLayer2Violation(sessionId, decision, raw);
                } else {
                    reporter.applyLayer2Recall(sessionId, decision, rewritten, raw);
                }
            }
            case DECISION_BLOCK -> reporter.applyLayer2Recall(sessionId, decision, RecallPhrases.BLOCK_RECALL, raw);
            case DECISION_ESCALATE -> reporter.applyLayer2Recall(sessionId, decision, RecallPhrases.ESCALATE_RECALL, raw);
            default -> reporter.reportLayer2Violation(sessionId, decision, raw);
        }
    }

    /**
     * 解析 SAF-002 完整审查结果（decision + rewritten_reply + escalation_reason）。
     * <p>
     * 容错：剥离 markdown 代码围栏；解析失败返回 null（视为未决，不上报）。
     */
    ReviewOutcome parseReview(String raw) {
        try {
            String json = stripCodeFence(raw);
            JsonNode node = objectMapper.readTree(json);
            JsonNode decision = node.get("decision");
            if (decision == null) {
                return null;
            }
            JsonNode rewritten = node.get("rewritten_reply");
            JsonNode reason = node.get("escalation_reason");
            return new ReviewOutcome(
                    decision.asText(),
                    rewritten != null && !rewritten.isNull() ? rewritten.asText() : null,
                    reason != null && !reason.isNull() ? reason.asText() : null);
        } catch (Exception e) {
            log.warn("Layer2 审查结果 JSON 解析失败: raw={}", abbreviate(raw), e);
            return null;
        }
    }

    /**
     * 从 LLM 响应中解析 decision 字段（保留供测试/外部简化调用）。
     */
    String parseDecision(String raw) {
        ReviewOutcome outcome = parseReview(raw);
        return outcome != null ? outcome.decision() : null;
    }

    /** 剥离 markdown 代码围栏（```json ... ```） */
    private String stripCodeFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        return s;
    }

    private static String abbreviate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
