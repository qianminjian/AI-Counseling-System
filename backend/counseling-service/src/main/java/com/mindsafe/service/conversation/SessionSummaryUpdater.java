package com.mindsafe.service.conversation;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.service.security.FieldEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 渐进式会话摘要更新器（CTX-Agent Phase 3）
 * <p>
 * 每 4 轮异步调用 LLM 生成当前会话的滚动摘要，存入 SessionState.sessionSummary，
 * 下一轮 System Prompt 注入时生效。解决 ChatMemory 20 条窗口外早期对话丢失问题。
 * <p>
 * 触发条件：turn - lastSummaryTurn >= 4（前 4 轮靠原始窗口足够）
 * 执行方式：@Async 异步，不阻塞当前轮响应
 * 失败安全：LLM 调用失败仅记日志，不影响对话
 */
@Service
public class SessionSummaryUpdater {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryUpdater.class);

    /** 摘要触发间隔（每 N 轮生成一次） */
    public static final int SUMMARY_INTERVAL = 4;

    private final AiChatService aiChatService;
    private final MessageSummaryMapper messageSummaryMapper;
    private final FieldEncryptionService fieldEncryptionService;
    private final RedisSessionStateStore sessionStateStore;

    public SessionSummaryUpdater(AiChatService aiChatService,
                                 MessageSummaryMapper messageSummaryMapper,
                                 FieldEncryptionService fieldEncryptionService,
                                 RedisSessionStateStore sessionStateStore) {
        this.aiChatService = aiChatService;
        this.messageSummaryMapper = messageSummaryMapper;
        this.fieldEncryptionService = fieldEncryptionService;
        this.sessionStateStore = sessionStateStore;
    }

    /**
     * 判断是否应该触发摘要更新。
     */
    public boolean shouldUpdate(SessionState session) {
        int turn = session.getTurnCount();
        int lastSummary = session.getLastSummaryTurn();
        return turn >= SUMMARY_INTERVAL && (turn - lastSummary) >= SUMMARY_INTERVAL;
    }

    /**
     * 异步生成滚动摘要并更新 SessionState。
     * <p>
     * 从 DB 查询当前会话所有消息摘要，拼接后调用 LLM 压缩，
     * 结果写回 Redis 中的 SessionState.sessionSummary。
     */
    @Async
    public void updateSummaryAsync(UUID tenantId, UUID sessionId, UUID studentUserId, int currentTurn) {
        try {
            // 1. 查询该会话所有消息摘要（按轮次排序）
            List<MessageSummary> messages = messageSummaryMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageSummary>()
                            .eq(MessageSummary::getTenantId, tenantId)
                            .eq(MessageSummary::getSessionId, sessionId)
                            .gt(MessageSummary::getTurnCount, 0) // 排除保密告知（turnCount=0）
                            .orderByAsc(MessageSummary::getTurnCount)
                            .orderByAsc(MessageSummary::getCreatedAt)
            );

            if (messages == null || messages.isEmpty()) {
                log.debug("CTX-Agent 摘要：无消息记录，跳过: sessionId={}", sessionId);
                return;
            }

            // 2. 拼接对话文本（解密）
            StringBuilder sb = new StringBuilder();
            for (MessageSummary m : messages) {
                String role = User.USER_TYPE_STUDENT.equals(m.getSenderType()) ? "学生" : "波波";
                String content = fieldEncryptionService.decrypt(m.getContentSummary());
                if (content != null && !content.isBlank()) {
                    sb.append(role).append(": ").append(content).append("\n");
                }
            }
            String conversationText = sb.toString();
            if (conversationText.isBlank()) return;

            // 3. 调用 LLM 生成摘要
            String summary = aiChatService.summarizeSessionProgress(conversationText);
            if (summary == null || summary.isBlank()) {
                log.debug("CTX-Agent 摘要：LLM 返回空，跳过: sessionId={}", sessionId);
                return;
            }

            // 4. 更新 SessionState（Redis）
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
}
