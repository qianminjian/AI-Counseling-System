package com.mindsafe.service.conversation;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.quality.ConversationQualityService;
import com.mindsafe.service.security.FieldEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 消息摘要持久化 + 会话摘要生成服务。
 * <p>
 * 从 ConversationServiceImpl 提取，职责：
 * <ul>
 *   <li>逐轮消息摘要落库（学生/AI，字段级加密）</li>
 *   <li>会话结束后异步生成 LLM 摘要 + 画像提炼 + 长期记忆提取</li>
 * </ul>
 */
@Service
public class MessageSummaryService {

    private static final Logger log = LoggerFactory.getLogger(MessageSummaryService.class);

    private final MessageSummaryMapper messageSummaryMapper;
    private final CounselingSessionMapper sessionMapper;
    private final AiChatService aiChatService;
    private final FieldEncryptionService fieldEncryptionService;
    private final ConversationQualityService conversationQualityService;
    private final ProfileExtractorService profileExtractorService;
    private final LongTermMemoryService longTermMemoryService;

    public MessageSummaryService(MessageSummaryMapper messageSummaryMapper,
                                 CounselingSessionMapper sessionMapper,
                                 AiChatService aiChatService,
                                 FieldEncryptionService fieldEncryptionService,
                                 ConversationQualityService conversationQualityService,
                                 ProfileExtractorService profileExtractorService,
                                 LongTermMemoryService longTermMemoryService) {
        this.messageSummaryMapper = messageSummaryMapper;
        this.sessionMapper = sessionMapper;
        this.aiChatService = aiChatService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.conversationQualityService = conversationQualityService;
        this.profileExtractorService = profileExtractorService;
        this.longTermMemoryService = longTermMemoryService;
    }

    /** 异步生成会话摘要（不阻塞主流程），摘要完成后触发画像 LLM 提炼（PROF-003） */
    @Async
    public void generateSummaryAsync(UUID tenantId, UUID sessionId, UUID studentUserId) {
        try {
            // 1. 查询该会话所有消息摘要
            List<MessageSummary> messages = messageSummaryMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageSummary>()
                            .eq(MessageSummary::getTenantId, tenantId)
                            .eq(MessageSummary::getSessionId, sessionId)
                            .orderByAsc(MessageSummary::getTurnCount)
                            .orderByAsc(MessageSummary::getCreatedAt)
            );
            if (messages.isEmpty()) return;

            // 2. 拼接对话文本
            StringBuilder sb = new StringBuilder();
            for (MessageSummary m : messages) {
                String role = User.USER_TYPE_STUDENT.equals(m.getSenderType()) ? "学生" : "AI";
                // R-01：contentSummary 落库时字段级加密，拼接前解密（明文数据兼容透传）
                sb.append(role).append(": ").append(fieldEncryptionService.decrypt(m.getContentSummary())).append("\n");
            }
            String conversationText = sb.toString();

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
                sessionMapper.updateById(update);
                log.info("会话摘要已生成: sessionId={}", sessionId);

                // 4. PROF-003：基于摘要 + 对话文本提炼画像增量（沟通偏好/韧性/社交图谱）
                profileExtractorService.extractAndMerge(tenantId, studentUserId, conversationText, summary);

                // 5. AI-008：提取跨会话关键事件（长期记忆）
                longTermMemoryService.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, conversationText, summary);
            }
        } catch (Exception e) {
            log.warn("会话摘要生成失败（不影响业务）: sessionId={}", sessionId, e);
        }
    }

    /** 持久化学生消息摘要（fire-and-forget，不影响主流程） */
    public void persistStudentMessageSummary(SessionState session, int turn,
                                             String content, String emotionLabel, int riskLevel) {
        try {
            MessageSummary summary = MessageSummary.studentMessage(
                    session.getTenantId(), session.getSessionId(), session.getStudentUserId(),
                    turn, content, emotionLabel, riskLevel
            );
            // R-01：学生消息内容字段级加密后落库（工厂已截断至 1024，再对截断后明文加密）
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
            MessageSummary summary = MessageSummary.aiMessage(
                    session.getTenantId(), session.getSessionId(), session.getStudentUserId(),
                    turn, aiResponse
            );
            // R-01：AI 回复内容字段级加密后落库
            summary.setContentSummary(fieldEncryptionService.encrypt(summary.getContentSummary()));
            messageSummaryMapper.insert(summary);
        } catch (Exception e) {
            log.warn("AI 回复摘要持久化失败: sessionId={}, turn={}", session.getSessionId(), turn, e);
        }
    }
}
