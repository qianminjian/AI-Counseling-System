package com.mindsafe.service.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.common.util.TextUtils;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.util.MessageSummarySummarizer;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.quality.ConversationQualityService;
import com.mindsafe.service.security.FieldEncryptionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息摘要持久化 + 会话摘要生成服务。
 * <p>
 * 从 ConversationServiceImpl 提取，职责：
 * <ul>
 *   <li>逐轮消息摘要落库（学生/AI，字段级加密）</li>
 *   <li>会话结束后异步生成 LLM 摘要，并编排一次提炼 LLM 调用（S1：双节点 JSON）
 *       分发画像合并与关键事件存储</li>
 * </ul>
 */
@Service
public class MessageSummaryService {

    private static final Logger log = LoggerFactory.getLogger(MessageSummaryService.class);

    private final MessageSummaryMapper messageSummaryMapper;
    // BA-11：会话表读写收口仓储（会话摘要字段落库不再直连 Mapper）
    private final CounselingSessionStore sessionStore;
    private final AiChatService aiChatService;
    private final FieldEncryptionService fieldEncryptionService;
    private final ConversationQualityService conversationQualityService;
    private final ProfileExtractorService profileExtractorService;
    private final LongTermMemoryService longTermMemoryService;
    private final ObjectMapper objectMapper;
    // ARCH-010 P2-5：会话摘要失败 metrics（失败率告警依据）
    private final Counter summaryFailureCounter;
    // BA-10：SessionSummaryUpdater 收编（渐进式滚动摘要写回 Redis 会话状态）
    private final RedisSessionStateStore sessionStateStore;

    /** 原文保真风险阈值：riskLevel ≥ 2（ORANGE/RED）不提炼（design/09 §3.3 接线表，BA-04 由实体上移） */
    private static final int FULL_FIDELITY_RISK_LEVEL = 2;

    /** 内容截断上限（字符；密文经 AES-256-GCM 膨胀，V32 起列类型 TEXT，AUDIT-P0-3） */
    private static final int MAX_CONTENT_LENGTH = 1024;

    /** 滚动摘要触发间隔（每 N 轮生成一次，BA-10 收编自 SessionSummaryUpdater.SUMMARY_INTERVAL） */
    public static final int SUMMARY_INTERVAL = 4;

    public MessageSummaryService(MessageSummaryMapper messageSummaryMapper,
                                 CounselingSessionStore sessionStore,
                                 AiChatService aiChatService,
                                 FieldEncryptionService fieldEncryptionService,
                                 ConversationQualityService conversationQualityService,
                                 ProfileExtractorService profileExtractorService,
                                 LongTermMemoryService longTermMemoryService,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry,
                                 RedisSessionStateStore sessionStateStore) {
        this.messageSummaryMapper = messageSummaryMapper;
        this.sessionStore = sessionStore;
        this.aiChatService = aiChatService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.conversationQualityService = conversationQualityService;
        this.profileExtractorService = profileExtractorService;
        this.longTermMemoryService = longTermMemoryService;
        this.objectMapper = objectMapper;
        this.summaryFailureCounter = Counter.builder("mindsafe.pipeline.failure")
                .tag("stage", "summary")
                .register(meterRegistry);
        this.sessionStateStore = sessionStateStore;
    }

    /** 异步生成会话摘要（不阻塞主流程），摘要完成后触发画像 LLM 提炼（PROF-003） */
    @Async
    public void generateSummaryAsync(UUID tenantId, UUID sessionId, UUID studentUserId) {
        try {
            // 1-2. 单点读取转写（BA-10：查→解密→拼接唯一实现，角色标注统一「学生/AI」）
            // M2（CodeReview）：走失败感知 strict 版——读取异常冒泡触发 summaryFailureCounter（ARCH-010 P2-5 观测性），
            // 空转写（确无消息）才正常跳过
            String conversationText = readSessionTranscriptStrict(tenantId, sessionId, TranscriptFilter.all());
            if (conversationText.isBlank()) return;

            // PEVAL-001：异步评估会话质量并落库（服务内部按抽样率决定是否评估，失败静默降级）
            conversationQualityService.evaluateSessionAsync(tenantId, sessionId, conversationText);

            // 3. 调用 LLM 生成摘要
            String summary = aiChatService.generateSessionSummary(conversationText);
            if (summary != null && !summary.isBlank()) {
                CounselingSession update = new CounselingSession();
                update.setSessionId(sessionId);
                // AUDIT-P1-8：session_summary 字段级加密后落库（教师端读取时解密）
                update.setSessionSummary(fieldEncryptionService.encrypt(summary));
                update.setUpdatedAt(Instant.now());
                sessionStore.updateById(update);
                log.info("会话摘要已生成: sessionId={}", sessionId);

                // 4. S1：一次提炼 LLM 调用（画像增量 + 关键事件双节点 JSON），解析后分发
                dispatchInsights(tenantId, sessionId, studentUserId, summary, conversationText);
            }
        } catch (Exception e) {
            summaryFailureCounter.increment();
            log.warn("会话摘要生成失败（不影响业务）: sessionId={}", sessionId, e);
        }
    }

    /**
     * S1 编排：解析提炼 LLM 的双节点结果并分发（任一节点缺失/解析失败 → 该路静默跳过）。
     * 由外层 catch 兜底 LLM 调用异常，不影响摘要落库主流程。
     */
    private void dispatchInsights(UUID tenantId, UUID sessionId, UUID studentUserId,
                                  String summary, String conversationText) {
        JsonNode insights = parseInsights(aiChatService.extractConversationInsights(conversationText, summary));
        if (insights == null) return;

        // 4.1 PROF-003：画像增量分发（profile_patch 缺失/非对象 → 跳过画像路）
        JsonNode patch = insights.get("profile_patch");
        if (patch != null && patch.isObject() && !patch.isEmpty()) {
            profileExtractorService.extractAndMerge(tenantId, studentUserId, patch);
        }

        // 4.2 AI-008：关键事件分发（key_events 缺失/空数组 → 跳过记忆路）
        JsonNode events = insights.get("key_events");
        if (events != null && events.isArray() && !events.isEmpty()) {
            longTermMemoryService.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, events);
        }
    }

    /** 解析提炼 JSON（兼容 ```json 代码块包裹）；非法输入返回 null（静默降级） */
    private JsonNode parseInsights(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        try {
            return objectMapper.readTree(TextUtils.stripCodeFence(rawJson));
        } catch (Exception e) {
            log.warn("会话提炼 JSON 解析失败（不影响业务）: {}", e.getMessage());
            return null;
        }
    }

    /** 持久化学生消息摘要（fire-and-forget，不影响主流程） */
    public void persistStudentMessageSummary(SessionState session, int turn,
                                             String content, String emotionLabel, int riskLevel) {
        try {
            // BA-04（DOC-074）：D-7 两级策略收敛到 service 单一入口——risk ≥ 2 原文保真截断 1024；risk < 2 语义提炼 ≤200 字（再截断兜底）
            String summarized = riskLevel >= FULL_FIDELITY_RISK_LEVEL
                    ? truncate(content, MAX_CONTENT_LENGTH)
                    : truncate(MessageSummarySummarizer.summarize(content), MAX_CONTENT_LENGTH);

            MessageSummary summary = new MessageSummary();
            summary.setSummaryId(UUID.randomUUID());
            summary.setTenantId(session.getTenantId());
            summary.setSessionId(session.getSessionId());
            summary.setStudentUserId(session.getStudentUserId());
            summary.setTurnCount(turn);
            summary.setSenderType("student");
            summary.setContentSummary(summarized);
            summary.setEmotionLabel(emotionLabel);
            summary.setRiskLevel(riskLevel);
            // JSON 拼串经 ObjectMapper（JsonbTypeHandler 消费合法 JSON，绕过手工拼串）
            summary.setEmotionTags(toJsonArray(emotionLabel));
            summary.setRiskSignals(riskLevel > 0 ? toRiskSignalsJson(riskLevel) : "[]");
            summary.setTopicTags("[]");
            summary.setCreatedAt(Instant.now());

            // R-01：学生消息内容字段级加密后落库
            summary.setContentSummary(fieldEncryptionService.encrypt(summary.getContentSummary()));
            messageSummaryMapper.insert(summary);
        } catch (Exception e) {
            log.warn("学生消息摘要持久化失败（不影响对话）: sessionId={}, turn={}", session.getSessionId(), turn, e);
        }
    }

    /** 持久化 AI 回复摘要 */
    public void persistAiMessageSummary(SessionState session, int turn, String aiResponse) {
        try {
            if (aiResponse == null || aiResponse.isBlank()) return;
            MessageSummary summary = new MessageSummary();
            summary.setSummaryId(UUID.randomUUID());
            summary.setTenantId(session.getTenantId());
            summary.setSessionId(session.getSessionId());
            summary.setStudentUserId(session.getStudentUserId());
            summary.setTurnCount(turn);
            summary.setSenderType("ai");
            summary.setContentSummary(truncate(aiResponse, MAX_CONTENT_LENGTH));
            summary.setRiskLevel(0);
            summary.setEmotionTags("[]");
            summary.setRiskSignals("[]");
            summary.setTopicTags("[]");
            summary.setCreatedAt(Instant.now());
            // R-01：AI 回复内容字段级加密后落库
            summary.setContentSummary(fieldEncryptionService.encrypt(summary.getContentSummary()));
            messageSummaryMapper.insert(summary);
        } catch (Exception e) {
            log.warn("AI 回复摘要持久化失败: sessionId={}, turn={}", session.getSessionId(), turn, e);
        }
    }

    // ===== BA-11：SAFE-201 保密边界告知收编（原 ConversationServiceImpl 直连 messageSummaryMapper） =====

    /**
     * 该学生是否已完成保密边界告知（存在 senderType='ai' + turnCount=0 的告知记录）。
     * SAFE-201：首次会话注入告知，复用 message_summary 表（turnCount=0 与正常 AI 摘要 turn≥1 具唯一区分性）。
     */
    public boolean hasConfidentialityNotice(UUID tenantId, UUID studentUserId) {
        Long count = messageSummaryMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getStudentUserId, studentUserId)
                        .eq(MessageSummary::getSenderType, "ai")
                        .eq(MessageSummary::getTurnCount, 0));
        return count != null && count > 0;
    }

    /**
     * 保密边界告知落库（合规审计凭据，固定字段：turnCount=0 + senderType=ai + 1024 截断）。
     * 告知为预审核模板明文，不做字段级加密（非学生隐私内容，审计凭据需可读）；BA-04 装配规则同 AI 摘要。
     */
    public void insertConfidentialityNotice(UUID tenantId, UUID studentUserId, UUID sessionId, String notice) {
        MessageSummary noticeRecord = new MessageSummary();
        noticeRecord.setSummaryId(UUID.randomUUID());
        noticeRecord.setTenantId(tenantId);
        noticeRecord.setSessionId(sessionId);
        noticeRecord.setStudentUserId(studentUserId);
        noticeRecord.setTurnCount(0);
        noticeRecord.setSenderType("ai");
        noticeRecord.setContentSummary(truncate(notice, MAX_CONTENT_LENGTH));
        noticeRecord.setRiskLevel(0);
        noticeRecord.setEmotionTags("[]");
        noticeRecord.setRiskSignals("[]");
        noticeRecord.setTopicTags("[]");
        noticeRecord.setCreatedAt(Instant.now());
        messageSummaryMapper.insert(noticeRecord);
    }

    // ===== BA-10：消息读取单点（查→解密→拼接唯一实现，角色标注统一「学生/AI」） =====

    /** 转写过滤条件：senderType=null 表示全部角色；minTurnCount 排除轮次过小的消息（如保密告知 turnCount=0） */
    public record TranscriptFilter(String senderType, int minTurnCount) {
        /** 全量转写（全部角色、不排除任何轮次） */
        public static TranscriptFilter all() {
            return new TranscriptFilter(null, 0);
        }
    }

    /**
     * 会话转写文本（查→解密→拼接唯一实现，BA-10）。
     * <p>
     * 角色标注统一「学生/AI」（防「学生/波波」文案漂移回潮）；解密后空白内容过滤；
     * 查询/解密异常降级空串（调用方按空转写跳过后续处理）。
     */
    public String readSessionTranscript(UUID tenantId, UUID sessionId, TranscriptFilter filter) {
        try {
            return readSessionTranscriptStrict(tenantId, sessionId, filter);
        } catch (Exception e) {
            log.warn("会话转写读取失败（降级空串）: sessionId={}, error={}", sessionId, e.getMessage());
            return "";
        }
    }

    /**
     * 会话转写读取失败感知版（M2/ARCH-010 P2-5 观测性）：查询/解密异常向上抛出，
     * 由调用方决定降级策略——摘要生成链路需区分「读取失败」（触发失败指标）与「确无消息」（正常跳过）。
     * 唯一实现：{@link #readSessionTranscript} 仅为本方法的 catch 降级包装，无重复逻辑。
     */
    public String readSessionTranscriptStrict(UUID tenantId, UUID sessionId, TranscriptFilter filter) {
        List<MessageSummary> messages = selectMessages(tenantId, sessionId, filter);
        StringBuilder sb = new StringBuilder();
        for (MessageSummary m : messages) {
            String role = User.USER_TYPE_STUDENT.equals(m.getSenderType()) ? "学生" : "AI";
            // R-01：contentSummary 落库时字段级加密，拼接前解密（明文数据兼容透传）
            String content = fieldEncryptionService.decrypt(m.getContentSummary());
            if (content != null && !content.isBlank()) {
                sb.append(role).append(": ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 学生消息明文列表（ORCH-008 会话深度量化输入，BA-10 收编自 loadStudentMessages）。
     * 复用单点查询解密；失败降级空列表（分析本身异步可降级）。
     */
    public List<String> readStudentPlainTexts(UUID tenantId, UUID sessionId) {
        try {
            List<MessageSummary> messages = selectMessages(tenantId, sessionId,
                    new TranscriptFilter(User.USER_TYPE_STUDENT, 0));
            List<String> texts = new ArrayList<>(messages.size());
            for (MessageSummary m : messages) {
                String plain = fieldEncryptionService.decrypt(m.getContentSummary());
                if (plain != null && !plain.isBlank()) {
                    texts.add(plain);
                }
            }
            return texts;
        } catch (Exception e) {
            log.debug("学生消息读取失败，降级为空列表: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 会话消息查询单点（BA-10：按过滤条件查询，按轮次+时间排序） */
    private List<MessageSummary> selectMessages(UUID tenantId, UUID sessionId, TranscriptFilter filter) {
        var qw = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageSummary>()
                .eq(MessageSummary::getTenantId, tenantId)
                .eq(MessageSummary::getSessionId, sessionId);
        if (filter.senderType() != null) {
            qw.eq(MessageSummary::getSenderType, filter.senderType());
        }
        if (filter.minTurnCount() > 0) {
            qw.gt(MessageSummary::getTurnCount, 0); // 排除保密告知（turnCount=0）
        }
        return messageSummaryMapper.selectList(qw.orderByAsc(MessageSummary::getTurnCount)
                .orderByAsc(MessageSummary::getCreatedAt));
    }

    // ===== BA-10：SessionSummaryUpdater 收编（渐进式滚动摘要，CTX-Agent Phase 3） =====

    /**
     * 是否应触发滚动摘要：turn ≥ 4 且距上次摘要 ≥ 4 轮（前 4 轮靠原始窗口足够）。
     * BA-10 收编自 SessionSummaryUpdater.shouldUpdate。
     */
    public boolean shouldUpdateProgressiveSummary(SessionState session) {
        int turn = session.getTurnCount();
        int lastSummary = session.getLastSummaryTurn();
        return turn >= SUMMARY_INTERVAL && (turn - lastSummary) >= SUMMARY_INTERVAL;
    }

    /**
     * 异步生成滚动摘要并更新 SessionState（BA-10 收编自 SessionSummaryUpdater.updateSummaryAsync）。
     * <p>
     * 转写走 readSessionTranscript 单点（minTurnCount=1 排除保密告知）；
     * LLM 压缩后写回 Redis SessionState.sessionSummary；失败安全不影响对话。
     */
    @Async
    public void updateProgressiveSummaryAsync(UUID tenantId, UUID sessionId, int currentTurn) {
        try {
            String conversationText = readSessionTranscript(tenantId, sessionId,
                    new TranscriptFilter(null, 1));
            if (conversationText.isBlank()) {
                log.debug("CTX-Agent 摘要：无消息记录，跳过: sessionId={}", sessionId);
                return;
            }

            String summary = aiChatService.summarizeSessionProgress(conversationText);
            if (summary == null || summary.isBlank()) {
                log.debug("CTX-Agent 摘要：LLM 返回空，跳过: sessionId={}", sessionId);
                return;
            }

            SessionState session = sessionStateStore.get(tenantId, sessionId);
            if (session != null) {
                session.setSessionSummary(summary.trim());
                session.setLastSummaryTurn(currentTurn);
                sessionStateStore.save(tenantId, sessionId, session);
                log.info("CTX-Agent 滚动摘要已更新: sessionId={}, turn={}, summaryLen={}",
                        sessionId, currentTurn, summary.length());
            }
        } catch (Exception e) {
            log.warn("CTX-Agent 滚动摘要失败（不影响对话）: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    // ===== BA-04：摘要装配私有工具（策略单一入口） =====

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /** 单元素 JSON 数组（null → "[]"）；序列化失败兜底 "[]" 不阻断落库 */
    private String toJsonArray(String value) {
        if (value == null) return "[]";
        try {
            return objectMapper.writeValueAsString(List.of(value));
        } catch (Exception e) {
            log.warn("情绪标签 JSON 序列化失败，降级空数组: {}", e.getMessage());
            return "[]";
        }
    }

    /** 风险信号 JSON：[{"level":N}]；序列化失败兜底 "[]" */
    private String toRiskSignalsJson(int level) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of("level", level)));
        } catch (Exception e) {
            log.warn("风险信号 JSON 序列化失败，降级空数组: {}", e.getMessage());
            return "[]";
        }
    }
}
