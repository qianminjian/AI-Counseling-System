package com.mindsafe.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *   <li>违规（非 pass）→ 通过 {@link OutputSafetyReporter} 写 risk_events 留痕（level=1，不通知教师）；</li>
 *   <li>MVP 不做召回/改写：消息已流出，召回价值有限且复杂；检测+留痕+人工复核是现阶段正确投入。</li>
 * </ul>
 */
@Component
public class OutputReviewService {

    private static final Logger log = LoggerFactory.getLogger(OutputReviewService.class);

    private static final String TEMPLATE_PATH = "/prompts/safety/safety_output_guard_zh-CN_v1.0.0.md";
    private static final String DECISION_PASS = "pass";

    private final ChatClient reviewClient;
    private final Executor outputReviewExecutor;
    private final OutputSafetyReporter reporter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String promptTemplate = "";

    public OutputReviewService(ChatClient.Builder chatClientBuilder,
                               @Qualifier("outputReviewExecutor") Executor outputReviewExecutor,
                               OutputSafetyReporter reporter) {
        // 独立 ChatClient 实例：审查调用与主对话互不干扰
        this.reviewClient = chatClientBuilder.build();
        this.outputReviewExecutor = outputReviewExecutor;
        this.reporter = reporter;
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
        CompletableFuture.runAsync(() -> review(sessionId, fullReply, emotionTag), outputReviewExecutor)
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

            String decision = parseDecision(raw);
            if (DECISION_PASS.equals(decision)) {
                log.debug("Layer2 审查通过: sessionId={}", sessionId);
                return;
            }

            log.warn("⚠️ Layer2 审查违规: sessionId={}, decision={}", sessionId, decision);
            try {
                reporter.reportLayer2Violation(sessionId, decision, raw);
            } catch (Exception e) {
                log.error("Layer2 违规上报失败: sessionId={}", sessionId, e);
            }
        } catch (Exception e) {
            // 审查失败仅记录，绝不影响主流程
            log.error("Layer2 审查执行失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 从 LLM 响应中解析 decision 字段。
     * <p>
     * 容错：剥离可能的 markdown 代码围栏；解析失败返回 null（视为未决，不上报）。
     */
    String parseDecision(String raw) {
        try {
            String json = stripCodeFence(raw);
            JsonNode node = objectMapper.readTree(json);
            JsonNode decision = node.get("decision");
            return decision != null ? decision.asText() : null;
        } catch (Exception e) {
            log.warn("Layer2 审查结果 JSON 解析失败: raw={}", abbreviate(raw), e);
            return null;
        }
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
