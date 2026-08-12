package com.mindsafe.service.conversation;

import com.mindsafe.domain.entity.CounselingSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SummaryCompensationJob 单元测试（P1-8：BA-11 收敛后获得独立测试面——变更热点 × 零测试组合收敛）。
 * <p>
 * 覆盖：扫描候选→逐条触发异步摘要（租户/会话/学生三参数透传）、空列表不触发、
 * 幂等不重补（已生成摘要不再命中由仓储 isNull 条件保证，二次扫描零触发）、SCAN_LIMIT 透传。
 */
class SummaryCompensationJobTest {

    private CounselingSessionStore sessionStore;
    private MessageSummaryService messageSummaryService;
    private SummaryCompensationJob job;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionStore = mock(CounselingSessionStore.class);
        messageSummaryService = mock(MessageSummaryService.class);
        job = new SummaryCompensationJob(sessionStore, messageSummaryService);
    }

    private CounselingSession session(String status) {
        CounselingSession s = new CounselingSession();
        s.setTenantId(tenantId);
        s.setStudentUserId(studentId);
        s.setSessionId(UUID.randomUUID());
        s.setSessionStatus(status);
        return s;
    }

    @Nested
    @DisplayName("扫描与触发")
    class ScanAndTrigger {

        @Test
        @DisplayName("三终态候选 → 逐条触发 generateSummaryAsync（租户/会话/学生三参数透传）")
        void terminalCandidates_triggerCompensationPerSession() {
            CounselingSession completed = session(CounselingSession.STATUS_COMPLETED);
            CounselingSession takenOver = session("taken_over");
            CounselingSession escalated = session("escalated");
            when(sessionStore.findSummaryCompensationCandidates(any(Instant.class), eq(200)))
                    .thenReturn(List.of(completed, takenOver, escalated));

            job.compensate();

            verify(messageSummaryService).generateSummaryAsync(
                    completed.getTenantId(), completed.getSessionId(), completed.getStudentUserId());
            verify(messageSummaryService).generateSummaryAsync(
                    takenOver.getTenantId(), takenOver.getSessionId(), takenOver.getStudentUserId());
            verify(messageSummaryService).generateSummaryAsync(
                    escalated.getTenantId(), escalated.getSessionId(), escalated.getStudentUserId());
        }

        @Test
        @DisplayName("无候选 → 不触发任何异步摘要")
        void emptyCandidates_noTrigger() {
            when(sessionStore.findSummaryCompensationCandidates(any(Instant.class), eq(200)))
                    .thenReturn(List.of());

            job.compensate();

            verify(messageSummaryService, never())
                    .generateSummaryAsync(any(UUID.class), any(UUID.class), any(UUID.class));
        }

        @Test
        @DisplayName("SCAN_LIMIT=200 每轮透传（防 LLM 突发堆积的扫描上限语义不变）")
        void scanLimitPassedToStore() {
            when(sessionStore.findSummaryCompensationCandidates(any(Instant.class), eq(200)))
                    .thenReturn(List.of());

            job.compensate();

            verify(sessionStore).findSummaryCompensationCandidates(any(Instant.class), eq(200));
        }
    }

    @Nested
    @DisplayName("幂等不重补")
    class Idempotency {

        @Test
        @DisplayName("二次扫描已无候选（摘要生成后 session_summary 非空不再命中）→ 零重复触发")
        void secondScanNoCandidates_noReTrigger() {
            CounselingSession stale = session(CounselingSession.STATUS_COMPLETED);
            when(sessionStore.findSummaryCompensationCandidates(any(Instant.class), eq(200)))
                    .thenReturn(List.of(stale), List.of());

            job.compensate();
            job.compensate();

            // 第一轮触发 1 次，第二轮候选为空不触发——无重复补偿
            verify(messageSummaryService, times(1))
                    .generateSummaryAsync(any(UUID.class), any(UUID.class), any(UUID.class));
        }

        @Test
        @DisplayName("扫描异常 → 静默降级不影响业务（catch 兜底，不抛给调度器）")
        void scanFailure_silentlyDegraded() {
            when(sessionStore.findSummaryCompensationCandidates(any(Instant.class), eq(200)))
                    .thenThrow(new RuntimeException("db down"));

            job.compensate();

            verify(messageSummaryService, never())
                    .generateSummaryAsync(any(UUID.class), any(UUID.class), any(UUID.class));
            assertThat(TenantContextAssert.systemScopeRestored()).isTrue();
        }
    }

    /** 辅助断言：runAsSystem 结束后系统作用域已恢复（防租户上下文泄漏到调度线程） */
    static final class TenantContextAssert {
        private TenantContextAssert() {
        }

        static boolean systemScopeRestored() {
            return !com.mindsafe.common.tenant.TenantContextHolder.isSystemScope();
        }
    }
}
