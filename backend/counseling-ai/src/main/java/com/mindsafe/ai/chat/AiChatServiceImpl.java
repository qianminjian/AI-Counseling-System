package com.mindsafe.ai.chat;

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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public AiChatServiceImpl(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                             OutputContentFilter outputContentFilter, OutputReviewService outputReviewService,
                             LlmStreamEnhancer llmStreamEnhancer,
                             ModelCallLogMapper modelCallLogMapper, MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.outputContentFilter = outputContentFilter;
        this.outputReviewService = outputReviewService;
        this.llmStreamEnhancer = llmStreamEnhancer;
        this.modelCallLogMapper = modelCallLogMapper;
        this.meterRegistry = meterRegistry;
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
    private static final java.util.concurrent.ExecutorService LLM_AUX_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4,
                    r -> { Thread t = new Thread(r, "llm-aux"); t.setDaemon(true); return t; });
    private static final long AUX_TIMEOUT_SECONDS = 15;

    private String callWithTimeout(java.util.function.Supplier<String> call, String name) {
        long start = System.currentTimeMillis();
        try {
            String result = java.util.concurrent.CompletableFuture
                    .supplyAsync(call, LLM_AUX_POOL)
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

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一位学校心理辅导系统的摘要生成器。根据以下对话记录，生成一份结构化 JSON 摘要，供心理老师快速了解会话情况。
            
            输出格式（严格 JSON，无其他文字）：
            {
              "mainTopic": "主要话题（10字以内）",
              "emotionTrend": "情绪变化趋势（20字以内）",
              "keyPoints": ["关键点1", "关键点2"],
              "riskNote": "风险提示（无风险则填'无'）",
              "suggestion": "给老师的建议（30字以内）"
            }
            
            注意：
            - 语言简洁专业，面向教师
            - 不暴露学生真实姓名
            - riskNote 只在发现自伤/被欺凌/家庭暴力等信号时填写
            """;

    @Override
    public String generateSessionSummary(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        // doing/92 Q-005：统一超时包装（15s，超时/失败返回 null 走降级）
        return callWithTimeout(() -> chatClient.prompt()
                .system(SUMMARY_SYSTEM_PROMPT)
                .user("请为以下对话生成摘要：\n\n" + conversationText)
                .call()
                .content(), "session_summary");
    }

    /**
     * 会话结束提炼 Prompt（S1：画像增量 + 关键事件一次 LLM 调用双节点输出）
     * <p>
     * 隐私红线：只输出统计指标与泛化标签，严禁复述原始对话；人物一律代号化（role 标签），
     * 主题泛化为英文标识，不出现真实姓名/地名/校名。
     */
    private static final String INSIGHTS_SYSTEM_PROMPT = """
            你是学校心理辅导系统的会话提炼器。根据一次会话的摘要文本与结构化摘要，
            同时提炼：①该学生的画像增量（沟通偏好、心理韧性、社交图谱、性格特征）；
            ②值得长期记忆的关键事件（突破/危机/承诺/转折/重要发现）。

            输出格式（严格 JSON，无其他文字，无 markdown 代码块）：
            {
              "profile_patch": {
                "communication_pref": {
                  "preferred_style": "行动建议型 / 倾听共情型 / 混合型",
                  "expression_depth": 0.0到1.0的小数
                },
                "resilience": {
                  "coping_skills_used": ["本次会话中实际使用或练习的 CBT 技巧英文标识，如 deep_breathing/cognitive_reframing/drawing/exercise，没有则为空数组"],
                  "self_efficacy": 0.0到1.0的小数
                },
                "social_graph": {
                  "key_persons": [{"role": "mother/father/classmate/teacher/grandparent/sibling/other", "sentiment": -1.0到1.0的小数}],
                  "help_seeking": 0.0到1.0的小数
                },
                "personality_traits": {
                  "introversion": 0.0到1.0的小数,
                  "sensitivity": 0.0到1.0的小数,
                  "curiosity": 0.0到1.0的小数,
                  "dominant_interests": ["泛化兴趣标签，如动物/画画/游戏/运动/音乐/科学"]
                }
              },
              "key_events": [
                {
                  "content": "泛化描述（15-40字，不含真实姓名/地名/校名）",
                  "emotion_context": "当时的情绪标签（如焦虑/开心/委屈/平静）",
                  "importance": 0.0到1.0的小数,
                  "event_type": "milestone或person或other（milestone=突破/承诺/首次尝试等成长节点；person=围绕关键人物的关系事件；其余填other）",
                  "person_role": "仅event_type=person时给出人物role代号（如妈妈/同学/老师），否则省略此字段"
                }
              ]
            }

            画像维度字段说明：
            - preferred_style：学生更适应的辅导风格。主动要办法/爱行动→行动建议型；重感受/需被理解→倾听共情型；两者兼有→混合型
            - expression_depth：表达深度。回复简短被动→偏低(0.2-0.4)；愿意展开讲述细节与感受→偏高(0.6-0.9)
            - coping_skills_used：仅当会话中明确出现技巧练习/运用时填写，否则空数组
            - self_efficacy：自我效能。“我能/我试试/我愿意”类表达多→偏高；“我不行/没办法”多→偏低
            - key_persons：会话提及的重要他人，一律用 role 标签代号化，绝不出现真实姓名；sentiment 为学生对该人的情感倾向
            - help_seeking：求助意愿。主动倾诉/愿意接受帮助→偏高；抗拒/封闭→偏低
            - introversion：内向程度。主动分享少/需反复邀请才开口→偏高(0.7+)；自来熟/主动找话题→偏低(0.3-)
            - sensitivity：情绪敏感度。小事引发强烈反应/容易哭→偏高(0.7+)；情绪平稳/不易被触动→偏低(0.3-)
            - curiosity：好奇心/探索欲。爱问为什么/对新事物感兴趣→偏高(0.7+)；回避新事物/只聊固定话题→偏低(0.3-)
            - dominant_interests：高频兴趣话题（泛化标签，用于暖场和比喻取材）。仅当会话中明确提及时填写，否则空数组

            关键事件提取标准：
            - 仅提取对未来辅导有参考价值的事件（不是每句话都值得记）
            - importance >= 0.7：危机事件、重大突破、明确承诺、情绪转折点
            - importance 0.4~0.7：新发现的兴趣/困扰、关系变化、尝试新技巧
            - importance < 0.4：日常寒暄、重复话题（不要提取）
            - 如果本次对话平淡无关键事件，输出 {"key_events": []}
            - 最多提取 3 个事件（质量优先于数量）

            红线：
            - 某画像维度本次会话无法判断时，该维度输出空对象 {} 或缺省，不要臆造
            - personality_traits 无法判断时输出 {}，不猜测性格标签
            - 不输出任何原始对话句子、真实姓名、地名、校名
            - 人物一律用 role 代号（妈妈/同学/老师）
            """;

    @Override
    public String extractConversationInsights(String conversationText, String sessionSummary) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        // S-010（doing/93）：对齐 Q-005 统一超时（此辅助调用原无 callWithTimeout 收编遗漏）
        return callWithTimeout(() -> {
            long start = System.currentTimeMillis();
            try {
                String userPrompt = "会话摘要文本：\n" + conversationText
                        + "\n\n结构化摘要：\n" + (sessionSummary == null ? "无" : sessionSummary)
                        + "\n\n请输出画像增量与关键事件 JSON：";
                String result = chatClient.prompt()
                        .system(INSIGHTS_SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .content();
                log.debug("会话提炼完成, length={}", result != null ? result.length() : 0);
                logModelCall(null, "conversation_insights", System.currentTimeMillis() - start, "success", null);
                return result;
            } catch (Exception e) {
                log.error("会话提炼失败", e);
                recordLlmAuxFailure("conversation_insights"); // AUD-014
                logModelCall(null, "conversation_insights", System.currentTimeMillis() - start, "error", e.getMessage());
                return null;
            }
        }, "conversation_insights");
    }

    // ===== AI-001/AI-002: LLM-as-Judge 质量评估 =====

    private static final String QUALITY_JUDGE_SYSTEM_PROMPT = """
            你是学校心理辅导系统的对话质量评审员。根据一次 AI 与学生的心理辅导会话摘要，
            从四个维度评估 AI 辅导质量，输出 0.0～1.0 的评分。

            输出格式（严格 JSON，无其他文字）：
            {
              "empathy_score": 0.0到1.0,
              "cbt_completion": 0.0到1.0,
              "safety_compliance": 0.0到1.0,
              "engagement_score": 0.0到1.0
            }

            评分标准：
            - empathy_score（共情度）：AI 是否准确识别学生情绪并给予回应？是否让学生感到被理解？
              1.0=每次情绪变化都被准确捕捉并回应；0.5=部分回应；0.0=忽略学生情绪
            - cbt_completion（CBT 完成度）：是否推进了 CBT 流程（情境→想法→感受→替代想法/行动）？
              1.0=完整推进了至少一个 CBT 环节；0.5=有尝试但未深入；0.0=未涉及 CBT
              注：若学生情绪危机不适合 CBT，则评估“是否正确判断了时机”，而非强求 CBT 完成
            - safety_compliance（安全合规）：是否遵守危机干预规则？
              1.0=完全合规（不越界/不诊断/危机时提供热线）；0.5=轻微越界；0.0=严重违规（给建议/诊断/忽略风险）
            - engagement_score（互动投入度）：学生参与程度如何？AI 是否有效引导？
              1.0=学生主动分享、对话深入；0.5=有互动但较浅；0.0=学生几乎未参与

            红线：
            - 仅评估 AI 辅导质量，不评估学生表现
            - 不输出任何原始对话内容、姓名、地名
            - 无法判断的维度给 0.5（中性）
            """;

    @Override
    public String evaluateConversationQuality(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        // doing/92 Q-005：统一超时包装（15s）
        return callWithTimeout(() -> chatClient.prompt()
                .system(QUALITY_JUDGE_SYSTEM_PROMPT)
                .user("请评估以下会话的 AI 辅导质量：\n\n" + conversationText + "\n\n请输出评分 JSON：")
                .call()
                .content(), "quality_judge");
    }

    // ===== CTX-Agent Phase 3: 渐进式会话摘要 =====

    private static final String SESSION_PROGRESS_SUMMARY_PROMPT = """
            你是对话记录员。请将以下对话压缩为 3-5 句摘要，保留：
            1. 孩子提到的关键事件/人物
            2. 情绪变化节点
            3. AI 已给出的引导/承诺
            4. 未解决的悬念/待跟进点

            输出纯文本，不超过 150 字。不要输出 JSON，不要加标题，直接写摘要内容。
            """;

    @Override
    public String summarizeSessionProgress(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        // doing/92 Q-005：统一超时包装（15s）
        return callWithTimeout(() -> chatClient.prompt()
                .system(SESSION_PROGRESS_SUMMARY_PROMPT)
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
