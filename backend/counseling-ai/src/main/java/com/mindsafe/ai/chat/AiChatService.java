package com.mindsafe.ai.chat;

import com.mindsafe.common.dto.chat.StreamMessageEvent;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * AI 聊天服务接口（Spring AI 封装）
 */
public interface AiChatService {

    /**
     * 流式对话（AI-005：接受预解析的 System Prompt，支持 A/B 版本路由）
     * <p>
     * ARCH-004：profilePrompt 参数已删除——生产恒传 null 的僵尸参数，画像 Prompt 由调用方
     * 直接拼入 systemPromptContent（见 ConversationServiceImpl 组装链）；chatProactive 的
     * 上下文简报由 contextBrief 参数独立承载（ARCH-010 D4 后与主链路同一版本路由加载路径）。
     *
     * @param sessionId           会话 ID
     * @param emotionTag          当前情绪标签
     * @param message             学生消息
     * @param systemPromptContent 预解析的 System Prompt 全文（已渲染变量，含语言模板与性别风格）
     * @return 流式事件
     */
    Flux<StreamMessageEvent> chatWithPrompt(UUID sessionId, String emotionTag, String message,
                                            String systemPromptContent);

    /**
     * 主动暖场对话（冷场引导，design/28 §三 3.4）
     * <p>
     * 关键差异（与 {@link #chatWithPrompt} 相比）：
     * <ul>
     *   <li><b>不向 ChatMemory 写入伪造的学生消息</b>（不污染对话记忆）；</li>
     *   <li>nudge 指令（TSK_004 渲染后，含 warmthLevel/direction）由调用方拼入 systemPromptContent；</li>
     *   <li>AI 回复正常写入记忆（孩子看到的连续性保留）；</li>
     *   <li>复用 Layer1 流式硬过滤 + Layer2 异步语义审查安全管线。</li>
     * </ul>
     * <p>
     * ARCH-010 D4：systemPromptContent 由调用方经 PromptVersionService 版本路由预渲染
     * （SYS_001 + LANG + GENDER_STYLE + TSK_004，DB 优先 + A/B 灰度），与主链路同一加载路径。
     *
     * @param sessionId           会话 ID
     * @param emotionTag          当前情绪标签
     * @param contextBrief        CTX-Agent 上下文简报（追加到 system 层尾部，recency bias）
     * @param systemPromptContent 预解析的 System Prompt 全文（含暖场指令与性别风格，已走 版本路由）
     * @return 流式事件
     */
    Flux<StreamMessageEvent> chatProactive(UUID sessionId, String emotionTag,
                                           String contextBrief, String systemPromptContent);

    /**
     * 清除会话记忆（会话结束时调用）
     *
     * @param sessionId 会话 ID
     */
    void clearMemory(UUID sessionId);

    /**
     * 生成会话结构化摘要（非流式，会话关闭后异步调用）
     *
     * @param conversationText 对话摘要文本（学生+AI 各轮次）
     * @return JSON 格式摘要（mainTopic/emotionTrend/riskNote/suggestion）
     */
    String generateSessionSummary(String conversationText);

    /**
     * 会话结束提炼（S1：画像增量 + 关键事件一次 LLM 调用双节点输出，非流式，会话关闭后异步调用）
     * <p>
     * 返回 JSON 含 {@code profile_patch}（communication_pref/resilience/social_graph/personality_traits）
     * 与 {@code key_events}（关键事件数组）两个节点，由编排层（MessageSummaryService）解析分发。
     * 仅输出结构化统计指标与泛化标签，严禁输出原始对话内容；人物代号化、主题泛化。
     *
     * @param conversationText 对话摘要文本
     * @param sessionSummary   会话结构化摘要（JSON，可为 null）
     * @return JSON（profile_patch + key_events），失败返回 null
     */
    String extractConversationInsights(String conversationText, String sessionSummary);

    /**
     * LLM-as-Judge 对话质量评估（AI-001/AI-002，非流式，会话关闭后异步调用）
     * <p>
     * 输出四维评分 JSON：empathy_score / cbt_completion / safety_compliance / engagement_score
     *
     * @param conversationText 对话摘要文本
     * @return JSON 评分结果，失败返回 null
     */
    String evaluateConversationQuality(String conversationText);

    /**
     * CTX-Agent Phase 3：渐进式会话摘要（非流式，每 4 轮异步调用）。
     * <p>
     * 将当前对话压缩为 3-5 句摘要，保留关键事件/人物/情绪变化/待跟进点，
     * 供下一轮 System Prompt 注入，解决 ChatMemory 窗口外早期对话丢失问题。
     *
     * @param conversationText 当前会话对话文本（截至当前轮）
     * @return 纯文本摘要（≤150字），失败返回 null
     */
    String summarizeSessionProgress(String conversationText);
}
