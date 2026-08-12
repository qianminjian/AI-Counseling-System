package com.mindsafe.service.safety;

import com.mindsafe.ai.memory.ChatMemoryAppender;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.service.risk.RiskEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    /** S-009（doing/93）：写入统一由 RiskEventWriter 承担（本测试仅验证 needsNotify 传参语义） */
    @Mock private RiskEventWriter riskEventWriter;
    @Mock private ChatMemoryAppender chatMemoryAppender;

    private OutputSafetyReporterImpl reporter;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reporter = new OutputSafetyReporterImpl(sessionMapper, riskEventWriter, chatMemoryAppender);
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
        @DisplayName("会话存在 → RED 事件经统一入口落库并通知（write(event, true)）")
        void block_reportsRedAndNotifies() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());

            reporter.reportLayer1Block(sessionId, "self_harm", "关键词", "片段");

            // S-009：落库 + 通知义务登记统一由 RiskEventWriter 承担（RED 需通知 → true）
            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventWriter).write(captor.capture(), eq(true));
            RiskEvent event = captor.getValue();
            assertThat(event.getRiskType()).isEqualTo("output_safety:self_harm");
            assertThat(event.getRiskLevel()).isEqualTo(RiskLevel.RED.severity());
            assertThat(event.getDetectedBy()).isEqualTo("output_filter");
        }

        @Test
        @DisplayName("教师通知失败语义已收敛至 RiskEventWriter（P0-4：内部 catch + markFailed），本层不感知不阻断")
        void notifyFailure_delegatedToWriter() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());

            reporter.reportLayer1Block(sessionId, "self_harm", "关键词", "片段");

            verify(riskEventWriter).write(any(RiskEvent.class), eq(true));
        }

        @Test
        @DisplayName("会话不存在 → 优雅降级，不落库不通知")
        void missingSession_skips() {
            when(sessionMapper.selectById(sessionId)).thenReturn(null);

            reporter.reportLayer1Block(sessionId, "self_harm", "关键词", "片段");

            verify(riskEventWriter, never()).write(any(RiskEvent.class), anyBoolean());
        }

        @Test
        @DisplayName("持久化异常 → 吞掉不抛出（不影响对话主流程）")
        void persistenceFailure_swallowed() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(riskEventWriter.write(any(RiskEvent.class), eq(true))).thenThrow(new RuntimeException("db down"));

            reporter.reportLayer1Block(sessionId, "violence", "关键词", "片段");
        }
    }

    @Nested
    @DisplayName("Layer2 异步审查留痕")
    class Layer2Violation {

        @Test
        @DisplayName("会话存在 → YELLOW 留痕不通知（write(event, false) 防补偿误重试）")
        void violation_recordsYellowWithoutNotify() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());

            reporter.reportLayer2Violation(sessionId, "rewrite", "{}");

            // S-009：低危留痕无通知义务 → write(event, false)，完成态标记由 writer 承担（P0-4）
            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventWriter).write(captor.capture(), eq(false));
            assertThat(captor.getValue().getRiskType()).isEqualTo("output_safety");
            assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.YELLOW.severity());
            assertThat(captor.getValue().getDetectedBy()).isEqualTo("output_review");
            // doing/92 R-015：审查 JSON 随事件落库（TC260 人工抽检依据）
            assertThat(captor.getValue().getReviewJson()).isEqualTo("{}");
        }

        @Test
        @DisplayName("会话不存在 → 优雅降级")
        void missingSession_skips() {
            when(sessionMapper.selectById(sessionId)).thenReturn(null);

            reporter.reportLayer2Violation(sessionId, "block", "{}");

            verify(riskEventWriter, never()).write(any(RiskEvent.class), anyBoolean());
        }
    }

    @Nested
    @DisplayName("Layer2 四决策召回（SAFE-202）")
    class Layer2Recall {

        private final String replacement = "抱歉，刚才的话不合适。我们换个方式聊聊。";

        @Test
        @DisplayName("rewrite → 追加更正消息，YELLOW 留痕不通知（write(event, false)）")
        void rewrite_appendsCorrectionYellowNoNotify() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryAppender.hasMessages(anyString())).thenReturn(true);

            reporter.applyLayer2Recall(sessionId, "rewrite", replacement, "{}");

            // doing/92 R-015：追加语义（原子幂等），不再 find+saveAll 整表替换
            ArgumentCaptor<AssistantMessage> appendCaptor = ArgumentCaptor.forClass(AssistantMessage.class);
            verify(chatMemoryAppender).append(eq(sessionId.toString()), appendCaptor.capture());
            assertThat(appendCaptor.getValue().getText()).isEqualTo(replacement);
            verify(chatMemoryAppender, never()).append(anyString(), org.mockito.ArgumentMatchers.isNull());

            // S-009：rewrite 无通知义务 → write(event, false)
            ArgumentCaptor<RiskEvent> eventCaptor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventWriter).write(eventCaptor.capture(), eq(false));
            assertThat(eventCaptor.getValue().getRiskType()).isEqualTo("output_safety:recall:rewrite");
            assertThat(eventCaptor.getValue().getRiskLevel()).isEqualTo(RiskLevel.YELLOW.severity());
            assertThat(eventCaptor.getValue().getReviewJson()).isEqualTo("{}");
        }

        @Test
        @DisplayName("block → 追加更正消息 + ORANGE 事件 + 教师通知（write(event, true)）")
        void block_orangeAndNotifies() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryAppender.hasMessages(anyString())).thenReturn(true);

            reporter.applyLayer2Recall(sessionId, "block", replacement, "{}");

            verify(chatMemoryAppender).append(eq(sessionId.toString()), org.mockito.ArgumentMatchers.any(AssistantMessage.class));
            // S-009：block=ORANGE 需教师通知 → write(event, true)
            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventWriter).write(captor.capture(), eq(true));
            assertThat(captor.getValue().getRiskType()).isEqualTo("output_safety:recall:block");
            assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.ORANGE.severity());
            assertThat(captor.getValue().getReviewJson()).isEqualTo("{}");
        }

        @Test
        @DisplayName("escalate → 追加更正消息 + RED 事件 + 教师通知（write(event, true)）")
        void escalate_redAndNotifies() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryAppender.hasMessages(anyString())).thenReturn(true);

            reporter.applyLayer2Recall(sessionId, "escalate", replacement, "{}");

            verify(chatMemoryAppender).append(eq(sessionId.toString()), org.mockito.ArgumentMatchers.any(AssistantMessage.class));
            // S-009：escalate=RED 需教师通知 → write(event, true)
            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventWriter).write(captor.capture(), eq(true));
            assertThat(captor.getValue().getRiskType()).isEqualTo("output_safety:recall:escalate");
            assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.RED.severity());
            assertThat(captor.getValue().getReviewJson()).isEqualTo("{}");
        }

        @Test
        @DisplayName("会话不存在 → 优雅降级，不动记忆不落库")
        void missingSession_skips() {
            when(sessionMapper.selectById(sessionId)).thenReturn(null);

            reporter.applyLayer2Recall(sessionId, "block", replacement, "{}");

            verifyNoInteractions(chatMemoryAppender, riskEventWriter);
        }

        @Test
        @DisplayName("会话记忆为空 → 跳过追加更正（避免悬空更正），事件仍落库")
        void emptyMemory_skipsAppendStillRecords() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryAppender.hasMessages(anyString())).thenReturn(false);

            reporter.applyLayer2Recall(sessionId, "rewrite", replacement, "{}");

            verify(chatMemoryAppender, never()).append(anyString(), org.mockito.ArgumentMatchers.any(AssistantMessage.class));
            ArgumentCaptor<RiskEvent> eventCaptor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventWriter).write(eventCaptor.capture(), eq(false));
            assertThat(eventCaptor.getValue().getRiskType()).isEqualTo("output_safety:recall:rewrite");
            assertThat(eventCaptor.getValue().getReviewJson()).isEqualTo("{}");
        }

        @Test
        @DisplayName("记忆追加异常 → 吞掉不抛出（不影响对话主流程）")
        void memoryFailure_swallowed() {
            when(sessionMapper.selectById(sessionId)).thenReturn(session());
            when(chatMemoryAppender.hasMessages(anyString())).thenReturn(true);
            doThrow(new RuntimeException("redis down"))
                    .when(chatMemoryAppender).append(anyString(), org.mockito.ArgumentMatchers.any(AssistantMessage.class));

            reporter.applyLayer2Recall(sessionId, "escalate", replacement, "{}");

            verify(riskEventWriter, never()).write(any(RiskEvent.class), anyBoolean());
        }
    }
}
