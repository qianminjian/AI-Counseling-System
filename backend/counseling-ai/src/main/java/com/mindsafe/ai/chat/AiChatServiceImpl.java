package com.mindsafe.ai.chat;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.safety.OutputContentFilter;
import com.mindsafe.ai.safety.OutputReviewService;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.ModelCallLog;
import com.mindsafe.domain.mapper.ModelCallLogMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * AI 聊天服务实现（Spring AI ChatClient 流式调用 + 多轮对话记忆 + 双层输出安全审查）
 * <p>
 * System Prompt 从 classpath 模板文件加载（SYS_001），运行时注入 emotion_tag 等变量。
 * 输出安全：Layer1 {@link OutputContentFilter} 流式实时硬过滤（命中即中断+安全话术）；
 * Layer2 {@link OutputReviewService} 流结束后异步 SAF-002 语义审查（检测+留痕，不阻塞）。
 */
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final OutputContentFilter outputContentFilter;
    private final OutputReviewService outputReviewService;
    private final LlmStreamEnhancer llmStreamEnhancer;
    private final ModelCallLogMapper modelCallLogMapper;
    private final MeterRegistry meterRegistry;
    private final PromptTemplateService promptTemplateService;
    /** 辅助 LLM 调用线程池（P1-3 板块02：受管 Bean，见 AiConfig#llmAuxExecutor） */
    private final Executor llmAuxExecutor;

    public AiChatServiceImpl(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                             OutputContentFilter outputContentFilter, OutputReviewService outputReviewService,
                             LlmStreamEnhancer llmStreamEnhancer,
                             ModelCallLogMapper modelCallLogMapper, MeterRegistry meterRegistry,
                             PromptTemplateService promptTemplateService,
                             @Qualifier("llmAuxExecutor") Executor llmAuxExecutor) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.outputContentFilter = outputContentFilter;
        this.outputReviewService = outputReviewService;
        this.llmStreamEnhancer = llmStreamEnhancer;
        this.modelCallLogMapper = modelCallLogMapper;
        this.meterRegistry = meterRegistry;
        this.promptTemplateService = promptTemplateService;
        this.llmAuxExecutor = llmAuxExecutor;
    }

    /**
     * AUD-014：LLM 辅助方法失败计数（fail-open 保留 return null，Prometheus 计数供告警；
     * 裁决：不改为 Optional——接口签名与全部调用方改动面大且无行为收益，计数+告警等价满足可观测目标）
     */
    private void recordLlmAuxFailure(String method) {
        meterRegistry.counter("mindsafe_llm_aux_failure_total", "method", method).increment();
    }

    /**
     * S-010（doing/93）：流式对话门面——双层安全管线 + 超时 + 重试 + 记忆回写 + 审查 +
     * 审计统一收敛（chatWithPrompt/chatProactive 双份管线合并；差异参数化）。
     *
     * @param recordMetrics 是否记录 chat 审计指标（主链路记录；暖场链路不记录）
     * @param logTag        日志前缀（区分链路，便于排障）
     */
    private Flux<StreamMessageEvent> streamChat(UUID sessionId, String sysPrompt, List<Message> history,
                                                String emotionTag, boolean recordMetrics, String logTag) {
        String conversationId = sessionId.toString();
        long streamStart = System.currentTimeMillis();
        StringBuilder responseCollector = new StringBuilder();

        return llmStreamEnhancer.enhance(
                () -> outputContentFilter.apply(
                        chatClient.prompt().system(sysPrompt).messages(history).stream().content(),
                        sessionId),
                sessionId)
                .doOnNext(evt -> {
                    if ("token".equals(evt.type()) && evt.content() != null) {
                        responseCollector.append(evt.content());
                    }
                })
                .doOnComplete(() -> {
                    String fullReply = responseCollector.toString();
                    chatMemory.add(conversationId, List.of(new AssistantMessage(fullReply)));
                    log.debug("{}完成: sessionId={}, responseLength={}", logTag, sessionId, fullReply.length());
                    outputReviewService.reviewAsync(sessionId, fullReply, emotionTag);
                    if (recordMetrics) {
                        logModelCall(sessionId, "chat", System.currentTimeMillis() - streamStart, "success", null);
                    }
                })
                .doOnError(e -> {
                    log.error("{}失败: sessionId={}", logTag, sessionId, e);
                    if (recordMetrics) {
                        logModelCall(sessionId, "chat", System.currentTimeMillis() - streamStart, "error", e.getMessage());
                    }
                });
    }

    @Override
    public Flux<StreamMessageEvent> chatWithPrompt(UUID sessionId, String emotionTag, String message,
                                                   String systemPromptContent) {
        String conversationId = sessionId.toString();
        log.debug("AI 对话请求(AI-005): sessionId={}, emotion={}, msgLength={}", sessionId, emotionTag, message.length());

        // 1. 保存用户消息到记忆，获取历史构建上下文
        chatMemory.add(conversationId, List.of(new UserMessage(message)));
        List<Message> history = chatMemory.get(conversationId);

        // 2. 预解析 System Prompt（SYS_001 + 语言模板 + 性别风格，B4：文案下沉 prompts/ 由调用方组装）
        // S-010：统一门面（双层过滤 + 超时重试 + 记忆回写 + 审查 + 审计）
        return streamChat(sessionId, systemPromptContent, history, emotionTag, true, "AI 回复(AI-005)");
    }

    @Override
    public Flux<StreamMessageEvent> chatProactive(UUID sessionId, String emotionTag,
                                                  String contextBrief, String systemPromptContent) {
        String conversationId = sessionId.toString();
        log.debug("主动暖场请求: sessionId={}, emotion={}", sessionId, emotionTag);

        // 1. 关键：不向 ChatMemory 写入伪造的学生消息（不污染对话记忆，design/28 §三 3.4）
        //    仅读取历史作为上下文
        List<Message> history = chatMemory.get(conversationId);

        // 2. 预解析 System Prompt（ARCH-010 D4：调用方已走 PromptVersionService 版本路由，
        //    含 SYS_001 + 语言模板 + GENDER_STYLE + TSK_004 暖场指令）；仅追加上下文简报
        //    （contextBrief 追加尾部，利用 recency bias，与主链路同一组装方式）
        String fullSystem = systemPromptContent;
        if (contextBrief != null && !contextBrief.isBlank()) {
            fullSystem = fullSystem + "\n\n" + contextBrief;
        }

        // 3. S-010：统一门面（暖场不记录 chat 审计指标；不写伪造学生消息）
        return streamChat(sessionId, fullSystem, history, emotionTag, false, "主动暖场回复");
    }

    /** 清除会话记忆（会话结束时调用） */
    /** doing/92 Q-005：同步辅助 LLM 调用统一超时（原 4 方法无超时——上游慢速时请求线程悬挂） */
    private static final long AUX_TIMEOUT_SECONDS = 15;

    private String callWithTimeout(java.util.function.Supplier<String> call, String name) {
        long start = System.currentTimeMillis();
        try {
            String result = java.util.concurrent.CompletableFuture
                    .supplyAsync(call, llmAuxExecutor)
                    .get(AUX_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            log.debug("{} 完成, length={}", name, result != null ? result.length() : 0);
            logModelCall(null, name, System.currentTimeMillis() - start, "success", null);
            return result;
        } catch (Exception e) {
            log.error("{} 失败或超时（>{}s）", name, AUX_TIMEOUT_SECONDS, e);
            recordLlmAuxFailure(name); // AUD-014
            logModelCall(null, name, System.currentTimeMillis() - start, "error", e.getMessage());
            return null;
        }
    }

    @Override
    public void clearMemory(UUID sessionId) {
        chatMemory.clear(sessionId.toString());
        log.debug("会话记忆已清除: sessionId={}", sessionId);
    }

    @Override
    public String generateSessionSummary(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        // P0-1 ①：辅助 prompt 下沉 prompts/aux/（AUX_001）由模板服务加载（缓存 + 版本路由语义预留），
        // 不再硬编码 Java 字符串——A/B 灰度与成本跟踪可覆盖辅助调用
        String systemPrompt = promptTemplateService.getTemplate(PromptTemplateService.AUX_001);
        // doing/92 Q-005：统一超时包装（15s，超时/失败返回 null 走降级）
        return callWithTimeout(() -> chatClient.prompt()
                .system(systemPrompt)
                .user("请为以下对话生成摘要：\n\n" + conversationText)
                .call()
                .content(), "session_summary");
    }

    /**
     * 会话结束提炼 Prompt（S1：画像增量 + 关键事件一次 LLM 调用双节点输出）
     * <p>
     * 隐私红线：只输出统计指标与泛化标签，严禁复述原始对话；人物一律代号化（role 标签），
     * 主题泛化为英文标识，不出现真实姓名/地名/校名。
     * <p>
     * P0-1 ①：prompt 文案下沉 prompts/aux/（AUX_002）；P1-1：三件套收敛到 callWithTimeout 唯一模板方法
     * （原内部 try-catch/recordLlmAuxFailure/logModelCall 双写残留已移除——一次调用仅一条审计）。
     */
    @Override
    public String extractConversationInsights(String conversationText, String sessionSummary) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        String systemPrompt = promptTemplateService.getTemplate(PromptTemplateService.AUX_002);
        // doing/92 Q-005：统一超时包装（15s，超时/失败返回 null 走降级）
        return callWithTimeout(() -> {
            String userPrompt = "会话摘要文本：\n" + conversationText
                    + "\n\n结构化摘要：\n" + (sessionSummary == null ? "无" : sessionSummary)
                    + "\n\n请输出画像增量与关键事件 JSON：";
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        }, "conversation_insights");
    }

    // ===== AI-001/AI-002: LLM-as-Judge 质量评估 =====

    @Override
    public String evaluateConversationQuality(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        // P0-1 ①：辅助 prompt 下沉 prompts/aux/（AUX_003）
        String systemPrompt = promptTemplateService.getTemplate(PromptTemplateService.AUX_003);
        // doing/92 Q-005：统一超时包装（15s）
        return callWithTimeout(() -> chatClient.prompt()
                .system(systemPrompt)
                .user("请评估以下会话的 AI 辅导质量：\n\n" + conversationText + "\n\n请输出评分 JSON：")
                .call()
                .content(), "quality_judge");
    }

    // ===== CTX-Agent Phase 3: 渐进式会话摘要 =====

    @Override
    public String summarizeSessionProgress(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        // P0-1 ①：辅助 prompt 下沉 prompts/aux/（AUX_004）
        String systemPrompt = promptTemplateService.getTemplate(PromptTemplateService.AUX_004);
        // doing/92 Q-005：统一超时包装（15s）
        return callWithTimeout(() -> chatClient.prompt()
                .system(systemPrompt)
                .user("请为以下对话生成进展摘要：\n\n" + conversationText)
                .call()
                .content(), "session_progress_summary");
    }

    /**
     * PROF-014 性别×年龄沟通风格（B4）：文案已下沉 prompts/style/ 模板并经 PromptVersionService
     * 版本路由（GENDER_STYLE_{MALE|FEMALE|NEUTRAL}_{LOW|MID|HIGH}），由调用方组装入
     * systemPromptContent，本类不再持有 Java 硬编码文案。
     */

    /**
     * 模型调用审计日志（降级不影响业务）。
     * tenantId 从 TenantContextHolder 读取；sessionId 为 null 时表示非会话关联调用。
     */
    private void logModelCall(UUID sessionId, String agentName, long latencyMs, String status, String errorMessage) {
        try {
            UUID tenantId = TenantContextHolder.get();
            ModelCallLog callLog = ModelCallLog.create(tenantId, sessionId, agentName, null, null, (int) latencyMs, status);
            if (errorMessage != null) {
                callLog.setErrorMessage(errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage);
            }
            modelCallLogMapper.insert(callLog);
        } catch (Exception e) {
            log.debug("ModelCallLog 审计写入降级（不影响业务）: {}", e.getMessage());
        }
    }
}
