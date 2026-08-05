package com.mindsafe.service.safety;

import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OutputSafetyReporterImpl 单测（SAFE-201/SAFE-202）。
 * <p>
 * 覆盖：Layer1 拦截上报 / Layer2 留痕 / Layer2 四决策召回（记忆替换 + 分级通知）/ 优雅降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("输出安全上报（Layer1/Layer2/召回）")
class OutputSafetyReporterImplTest {

    @Mock private CounselingSessionMapper sessionMapper;
    @Mock private RiskEventMapper riskEventMapper;
    @Mock private NotificationService notificationService;
    @Mock private RiskNotifyOutboxService riskNotifyOutboxService;
    @Mock private ChatMemoryRepository chatMemoryRepository;

    private OutputSafetyReporterImpl reporter;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reporter = new OutputSafetyReporterImpl(sessionMapper, riskEventMapper,
                notificationService, riskNotifyOutboxService, chatMemoryRepository);
    }

    private CounselingSession session() {
        CounselingSession s = new CounselingSession();
        s.setTenantId(tenantId);
        s.setStudentUserId(studentId);
        return s;
    }

    @Nested
    @DisplayName("Layer1 实时拦截上报")
    class Layer1 {

        @Test
        @DisplayName("会话存在 → RED 事件落库 + 教师通知 + outbox 标记 sent")
        void block_reportsRedAndNotifies() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());

            reporter.reportLayer1Block(sessionId, "self_harm", "关键词", "片段");

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventMapper).insert(captor.capture());
            RiskEvent event = captor.getValue();
            assertThat(event.getRiskType()).isEqualTo("output_safety:self_harm");
            assertThat(event.getRiskLevel()).isEqualTo(RiskLevel.RED.severity());
            assertThat(event.getDetectedBy()).isEqualTo("output_filter");
            verify(notificationService).notifyRiskEvent(event);
            // P0-4：通知成功 → 状态标记 sent
            verify(riskNotifyOutboxService).markSent(event);
        }

        @Test
        @DisplayName("教师通知失败：不抛出（不影响对话流），标记 failed 进补偿队列（P0-4）")
        void notifyFailure_marksFailed() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            doThrow(new RuntimeException("企业微信不可用"))
                    .when(notificationService).notifyRiskEvent(any(RiskEvent.class));

            reporter.reportLayer1Block(sessionId, "self_harm", "关键词", "片段");

            verify(riskEventMapper).insert(any(RiskEvent.class));
            verify(riskNotifyOutboxService).markFailed(any(RiskEvent.class));
        }

        @Test
        @DisplayName("会话不存在 → 优雅降级，不落库不通知")
        void missingSession_skips() {
            when(sessionMapper.selectById(sessionId)).thenReturn(null);

            reporter.reportLayer1Block(sessionId, "self_harm", "关键词", "片段");

            verify(riskEventMapper, never()).insert(any(RiskEvent.class));
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("持久化异常 → 吞掉不抛出（不影响对话主流程）")
        void persistenceFailure_swallowed() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(riskEventMapper.insert(any(RiskEvent.class))).thenThrow(new RuntimeException("db down"));

            reporter.reportLayer1Block(sessionId, "violence", "关键词", "片段");

            verifyNoInteractions(notificationService);
        }
    }

    @Nested
    @DisplayName("Layer2 异步审查留痕")
    class Layer2Violation {

        @Test
        @DisplayName("会话存在 → YELLOW 留痕，不触发教师通知（人工抽检），outbox 标记完成防误重试")
        void violation_recordsYellowWithoutNotify() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());

            reporter.reportLayer2Violation(sessionId, "rewrite", "{}");

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventMapper).insert(captor.capture());
            assertThat(captor.getValue().getRiskType()).isEqualTo("output_safety");
            assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.YELLOW.severity());
            assertThat(captor.getValue().getDetectedBy()).isEqualTo("output_review");
            verifyNoInteractions(notificationService);
            // P0-4：无通知义务的事件标记 sent（完成态），防止补偿任务误重试留痕事件
            verify(riskNotifyOutboxService).markSent(captor.getValue());
        }

        @Test
        @DisplayName("会话不存在 → 优雅降级")
        void missingSession_skips() {
            when(sessionMapper.selectById(sessionId)).thenReturn(null);

            reporter.reportLayer2Violation(sessionId, "block", "{}");

            verify(riskEventMapper, never()).insert(any(RiskEvent.class));
        }
    }

    @Nested
    @DisplayName("Layer2 四决策召回（SAFE-202）")
    class Layer2Recall {

        private final String replacement = "抱歉，刚才的话不合适。我们换个方式聊聊。";

        @Test
        @DisplayName("rewrite → 替换记忆最后一条 AI 回复，YELLOW 留痕不通知，outbox 标记完成")
        void rewrite_replacesMemoryYellowNoNotify() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryRepository.findByConversationId(sessionId.toString()))
                    .thenReturn(List.of(new UserMessage("你好"), new AssistantMessage("原始不当回复")));

            reporter.applyLayer2Recall(sessionId, "rewrite", replacement, "{}");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Message>> saveCaptor = ArgumentCaptor.forClass(List.class);
            verify(chatMemoryRepository).saveAll(eq(sessionId.toString()), saveCaptor.capture());
            List<Message> saved = saveCaptor.getValue();
            assertThat(saved).hasSize(2);
            assertThat(((AssistantMessage) saved.get(1)).getText()).isEqualTo(replacement);

            ArgumentCaptor<RiskEvent> eventCaptor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventMapper).insert(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getRiskType()).isEqualTo("output_safety:recall:rewrite");
            assertThat(eventCaptor.getValue().getRiskLevel()).isEqualTo(RiskLevel.YELLOW.severity());
            verifyNoInteractions(notificationService);
            // P0-4：无通知义务 → 标记完成态
            verify(riskNotifyOutboxService).markSent(eventCaptor.getValue());
        }

        @Test
        @DisplayName("block → ORANGE 事件 + 教师通知 + outbox 标记 sent")
        void block_orangeAndNotifies() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryRepository.findByConversationId(anyString())).thenReturn(List.of());

            reporter.applyLayer2Recall(sessionId, "block", replacement, "{}");

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventMapper).insert(captor.capture());
            assertThat(captor.getValue().getRiskType()).isEqualTo("output_safety:recall:block");
            assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.ORANGE.severity());
            verify(notificationService).notifyRiskEvent(captor.getValue());
            verify(riskNotifyOutboxService).markSent(captor.getValue());
        }

        @Test
        @DisplayName("escalate → RED 事件 + 教师通知 + outbox 标记 sent")
        void escalate_redAndNotifies() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryRepository.findByConversationId(anyString())).thenReturn(List.of());

            reporter.applyLayer2Recall(sessionId, "escalate", replacement, "{}");

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventMapper).insert(captor.capture());
            assertThat(captor.getValue().getRiskType()).isEqualTo("output_safety:recall:escalate");
            assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.RED.severity());
            verify(notificationService).notifyRiskEvent(captor.getValue());
            verify(riskNotifyOutboxService).markSent(captor.getValue());
        }

        @Test
        @DisplayName("记忆中无 AI 回复 → 不 saveAll，但事件仍落库")
        void noAssistantMessage_skipsReplaceStillRecords() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryRepository.findByConversationId(anyString()))
                    .thenReturn(List.of(new UserMessage("你好")));

            reporter.applyLayer2Recall(sessionId, "block", replacement, "{}");

            verify(chatMemoryRepository, never()).saveAll(anyString(), anyList());
            verify(riskEventMapper).insert(any(RiskEvent.class));
        }

        @Test
        @DisplayName("多条消息 → 只替换最后一条 AI 回复（更早的 AI 消息不动）")
        void replacesOnlyLastAssistantMessage() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryRepository.findByConversationId(anyString()))
                    .thenReturn(List.of(
                            new AssistantMessage("更早的回复"),
                            new UserMessage("学生消息"),
                            new AssistantMessage("最后的不当回复")));

            reporter.applyLayer2Recall(sessionId, "rewrite", replacement, "{}");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Message>> saveCaptor = ArgumentCaptor.forClass(List.class);
            verify(chatMemoryRepository).saveAll(eq(sessionId.toString()), saveCaptor.capture());
            List<Message> saved = saveCaptor.getValue();
            assertThat(((AssistantMessage) saved.get(0)).getText()).isEqualTo("更早的回复");
            assertThat(((AssistantMessage) saved.get(2)).getText()).isEqualTo(replacement);
        }

        @Test
        @DisplayName("会话不存在 → 优雅降级，不动记忆不落库")
        void missingSession_skips() {
            when(sessionMapper.selectById(sessionId)).thenReturn(null);

            reporter.applyLayer2Recall(sessionId, "block", replacement, "{}");

            verifyNoInteractions(chatMemoryRepository, riskEventMapper, notificationService);
        }

        @Test
        @DisplayName("记忆仓库异常 → 吞掉不抛出（不影响对话主流程）")
        void memoryFailure_swallowed() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            doThrow(new RuntimeException("redis down"))
                    .when(chatMemoryRepository).findByConversationId(anyString());

            reporter.applyLayer2Recall(sessionId, "escalate", replacement, "{}");

            verify(riskEventMapper, never()).insert(any(RiskEvent.class));
        }
    }
}
