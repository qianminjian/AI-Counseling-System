package com.mindsafe.service.relaxation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.domain.entity.RelaxationSession;
import com.mindsafe.domain.mapper.RelaxationSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RelaxationService 放松练习单测：练习记录创建 + 今日完成计数。
 */
@ExtendWith(MockitoExtension.class)
class RelaxationServiceTest {

    @Mock private RelaxationSessionMapper relaxationSessionMapper;

    private RelaxationService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RelaxationService(relaxationSessionMapper);
    }

    @Test
    @DisplayName("recordSession：创建实体并落库")
    void recordSession() {
        RelaxationSession result = service.recordSession(tenantId, studentId, "breathing", 300, true);

        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getStudentUserId()).isEqualTo(studentId);
        assertThat(result.getExerciseType()).isEqualTo("breathing");
        assertThat(result.getDurationSeconds()).isEqualTo(300);
        assertThat(result.getCompleted()).isTrue();
        verify(relaxationSessionMapper).insert(result);
    }

    @Test
    @DisplayName("recordSession：未完成练习也落库")
    void recordSession_incomplete() {
        RelaxationSession result = service.recordSession(tenantId, studentId, "muscle", 120, false);

        assertThat(result.getCompleted()).isFalse();
        verify(relaxationSessionMapper).insert(result);
    }

    @Test
    @DisplayName("countTodayCompleted：委托 selectCount 返回计数")
    void countTodayCompleted() {
        when(relaxationSessionMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        long count = service.countTodayCompleted(tenantId, studentId);

        assertThat(count).isEqualTo(3L);
        verify(relaxationSessionMapper).selectCount(any(Wrapper.class));
    }

    @Test
    @DisplayName("countTodayCompleted：无记录返回 0")
    void countTodayCompleted_zero() {
        when(relaxationSessionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThat(service.countTodayCompleted(tenantId, studentId)).isZero();
    }
}
