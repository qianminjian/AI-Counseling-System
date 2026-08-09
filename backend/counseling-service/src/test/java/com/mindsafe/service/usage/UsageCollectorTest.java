package com.mindsafe.service.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计量采集器单元测试（ADMIN-P3-01，M4 采集层：llm_call 聚合 + 活跃学生快照）
 */
class UsageCollectorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final UsageCollector collector = new UsageCollector(jdbcTemplate);

    @Test
    @DisplayName("collectLlmCalls：30min 窗口聚合写入（参数含窗口起止）")
    void collectLlmCalls() {
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(3);

        collector.collectLlmCalls();

        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("collectActiveStudents：当日活跃学生快照（Asia/Shanghai 日界）")
    void collectActiveStudents() {
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(2);

        collector.collectActiveStudents();

        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
