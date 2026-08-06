package com.mindsafe.service.conversation;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.service.security.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionSummaryUpdater 单元测试（CTX-Agent Phase 3：渐进式滚动摘要）
 * <p>
 * 覆盖：触发间隔判断（每 4 轮）、异步摘要全流程（查询→解密→LLM→写回 Redis）、
 * 各失败分支（无消息/空内容/LLM 返回空/会话不存在/异常）不影响对话的失败安全语义。
 */
@ExtendWith(MockitoExtension.class)
class SessionSummaryUpdaterTest {

    @Mock
    private AiChatService aiChatService;
    @Mock
    private MessageSummaryMapper messageSummaryMapper;
    @Mock
    private FieldEncryptionService fieldEncryptionService;
    @Mock
    private RedisSessionStateStore sessionStateStore;

    private SessionSummaryUpdater updater;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        updater = new SessionSummaryUpdater(aiChatService, messageSummaryMapper,
                fieldEncryptionService, sessionStateStore);
    }

    private SessionState newState(int turnCount, int lastSummaryTurn) {
        SessionState state = new SessionState(sessionId, tenantId, studentUserId,
                "neutral", "text", "F", 0.5, 3);
        state.setTurnCount(turnCount);
        state.setLastSummaryTurn(lastSummaryTurn);
        return state;
    }

    private MessageSummary newMessage(String senderType, String encryptedContent) {
        MessageSummary m = new MessageSummary();
        m.setTenantId(tenantId);
        m.setSessionId(sessionId);
        m.setSenderType(senderType);
        m.setContentSummary(encryptedContent);
        return m;
    }

    @Nested
    @DisplayName("shouldUpdate 触发判断")
    class ShouldUpdate {

        @Test
        @DisplayName("轮次不足 4 轮不触发")
        void turnBelowInterval() {
            assertThat(updater.shouldUpdate(newState(3, 0))).isFalse();
        }

        @Test
        @DisplayName("恰好第 4 轮且从未摘要过 → 触发")
        void exactFourthTurn() {
            assertThat(updater.shouldUpdate(newState(4, 0))).isTrue();
        }

        @Test
        @DisplayName("距上次摘要不足 4 轮不触发")
        void tooSoonSinceLastSummary() {
            assertThat(updater.shouldUpdate(newState(7, 4))).isFalse();
        }

        @Test
        @DisplayName("距上次摘要达到 4 轮 → 触发")
        void intervalReached() {
            assertThat(updater.shouldUpdate(newState(8, 4))).isTrue();
        }
    }

    @Nested
    @DisplayName("updateSummaryAsync 异步摘要生成")
    class UpdateSummaryAsync {

        @Test
        @DisplayName("正常流程：查询消息→解密拼接→LLM 摘要→写回 Redis")
        void happyPath() {
            MessageSummary studentMsg = newMessage("student", "enc-1");
            MessageSummary aiMsg = newMessage("assistant", "enc-2");
            when(messageSummaryMapper.selectList(any())).thenReturn(List.of(studentMsg, aiMsg));
            when(fieldEncryptionService.decrypt("enc-1")).thenReturn("我今天有点难过");
            when(fieldEncryptionService.decrypt("enc-2")).thenReturn("愿意多说说吗");
            when(aiChatService.summarizeSessionProgress(anyString()))
                    .thenReturn("  学生表达了难过情绪，AI 引导其展开叙述  ");
            SessionState session = newState(8, 4);
            when(sessionStateStore.get(tenantId, sessionId)).thenReturn(session);

            updater.updateSummaryAsync(tenantId, sessionId, studentUserId, 8);

            assertThat(session.getSessionSummary()).isEqualTo("学生表达了难过情绪，AI 引导其展开叙述");
            assertThat(session.getLastSummaryTurn()).isEqualTo(8);
            verify(sessionStateStore).save(eq(tenantId), eq(sessionId), eq(session));
            // 拼接文本包含学生与波波角色标签
            org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(aiChatService).summarizeSessionProgress(captor.capture());
            assertThat(captor.getValue()).contains("学生: 我今天有点难过").contains("波波: 愿意多说说吗");
        }

        @Test
        @DisplayName("无消息记录 → 跳过，不调用 LLM")
        void noMessages() {
            when(messageSummaryMapper.selectList(any())).thenReturn(Collections.emptyList());

            updater.updateSummaryAsync(tenantId, sessionId, studentUserId, 8);

            verify(aiChatService, never()).summarizeSessionProgress(anyString());
            verify(sessionStateStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("消息列表为 null → 跳过")
        void nullMessages() {
            when(messageSummaryMapper.selectList(any())).thenReturn(null);

            updater.updateSummaryAsync(tenantId, sessionId, studentUserId, 8);

            verify(aiChatService, never()).summarizeSessionProgress(anyString());
        }

        @Test
        @DisplayName("解密后内容全为空 → 不调用 LLM")
        void allContentBlank() {
            when(messageSummaryMapper.selectList(any()))
                    .thenReturn(List.of(newMessage("student", "enc-blank")));
            when(fieldEncryptionService.decrypt("enc-blank")).thenReturn("   ");

            updater.updateSummaryAsync(tenantId, sessionId, studentUserId, 8);

            verify(aiChatService, never()).summarizeSessionProgress(anyString());
            verify(sessionStateStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("LLM 返回空摘要 → 不写回 Redis")
        void llmReturnsBlank() {
            when(messageSummaryMapper.selectList(any()))
                    .thenReturn(List.of(newMessage("student", "enc-1")));
            when(fieldEncryptionService.decrypt("enc-1")).thenReturn("内容");
            when(aiChatService.summarizeSessionProgress(anyString())).thenReturn("");

            updater.updateSummaryAsync(tenantId, sessionId, studentUserId, 8);

            verify(sessionStateStore, never()).get(any(), any());
            verify(sessionStateStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("Redis 中会话不存在 → 不保存")
        void sessionNotFound() {
            when(messageSummaryMapper.selectList(any()))
                    .thenReturn(List.of(newMessage("student", "enc-1")));
            when(fieldEncryptionService.decrypt("enc-1")).thenReturn("内容");
            when(aiChatService.summarizeSessionProgress(anyString())).thenReturn("摘要内容");
            when(sessionStateStore.get(tenantId, sessionId)).thenReturn(null);

            updater.updateSummaryAsync(tenantId, sessionId, studentUserId, 8);

            verify(sessionStateStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("数据库异常 → 静默吞掉，不影响对话（失败安全）")
        void exceptionSwallowed() {
            when(messageSummaryMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

            // 不应抛出异常
            updater.updateSummaryAsync(tenantId, sessionId, studentUserId, 8);

            verify(sessionStateStore, never()).save(any(), any(), any());
        }
    }
}
