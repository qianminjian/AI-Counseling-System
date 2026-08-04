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
     *
     * @param sessionId           会话 ID
     * @param emotionTag          当前情绪标签
     * @param message             学生消息
     * @param gender              学生性别
     * @param profilePrompt       学生画像 Prompt 片段（可为 null）
     * @param grade               学生年级
     * @param systemPromptContent 预解析的 System Prompt 全文（已渲染变量，含语言模板）
     * @return 流式事件
     */
    Flux<StreamMessageEvent> chatWithPrompt(UUID sessionId, String emotionTag, String message,
                                            String gender, String profilePrompt, int grade,
                                            String systemPromptContent);

    /**
     * 主动暖场对话（冷场引导，design/28 §三 3.4）
     * <p>
     * 关键差异（与 {@link #chatWithPrompt} 相比）：
     * <ul>
     *   <li><b>不向 ChatMemory 写入伪造的学生消息</b>（不污染对话记忆）；</li>
     *   <li>nudge 指令（TSK-004 渲染后，含 warmthLevel/direction）追加到 system 层；</li>
     *   <li>AI 回复正常写入记忆（孩子看到的连续性保留）；</li>
     *   <li>复用 Layer1 流式硬过滤 + Layer2 异步语义审查安全管线。</li>
     * </ul>
     *
     * @param sessionId        会话 ID
     * @param emotionTag       当前情绪标签
     * @param gender           学生性别（male/female，可为 null）
     * @param profilePrompt    学生画像 Prompt 片段（可为 null）
     * @param nudgeInstruction 暖场指令（TSK-004 渲染后，追加到 system 层）
     * @param grade            学生年级（1-6，解析失败默认 4）
     * @return 流式事件
     */
    Flux<StreamMessageEvent> chatProactive(UUID sessionId, String emotionTag, String gender,
                                           String profilePrompt, String nudgeInstruction, int grade);

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
     * 提炼学生画像增量 patch（PROF-003，非流式，会话关闭后异步调用）
     * <p>
     * 仅输出结构化统计指标与泛化标签，严禁输出原始对话内容；人物代号化、主题泛化。
     *
     * @param conversationText 对话摘要文本
     * @param sessionSummary   会话结构化摘要（JSON）
     * @return JSON patch（communication_pref/resilience/social_graph），失败返回 null
     */
    String extractProfilePatch(String conversationText, String sessionSummary);

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
     * 提取跨会话关键事件（AI-008，非流式，会话关闭后异步调用）
     * <p>
     * 从对话中提取值得长期记忆的关键事件（突破/危机/承诺/转折），
     * 输出泛化描述（不含真实姓名/地名），供后续会话 Prompt 回注。
     *
     * @param conversationText 对话摘要文本
     * @param sessionSummary   会话结构化摘要（JSON，可为 null）
     * @return JSON 数组（key_events），失败返回 null
     */
    String extractKeyEvents(String conversationText, String sessionSummary);

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
