package com.mindsafe.service.usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UsageTimeLimitService 每日使用时长限制单测（AUTH-030）：累加/查询/上限判定/剩余配额/预警占位，
 * 含 fail-open（Redis 异常放行）与管理员关闭（max<=0）分支。
 */
@ExtendWith(MockitoExtension.class)
class UsageTimeLimitServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter counter;

    private UsageTimeLimitService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // lenient：部分用例（addUsage_invalid 等）不使用这些 stub
        lenient().when(meterRegistry.counter(anyString())).thenReturn(counter);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new UsageTimeLimitService(redisTemplate, 30, 300, meterRegistry);
    }

    @Test
    @DisplayName("addUsage：首次累加设置 TTL（increment 返回 seconds 本身）")
    void addUsage_first() {
        when(valueOps.increment(anyString(), any(Long.class))).thenReturn(10L);

        service.addUsage(tenantId, userId, 10);

        verify(valueOps).increment(anyString(), org.mockito.ArgumentMatchers.eq(10L));
        verify(redisTemplate).expire(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("addUsage：非首次累加不重设 TTL")
    void addUsage_again() {
        when(valueOps.increment(anyString(), any(Long.class))).thenReturn(20L);

        service.addUsage(tenantId, userId, 10);

        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("addUsage：seconds<=0 或 max<=0 直接忽略")
    void addUsage_invalid() {
        service.addUsage(tenantId, userId, 0);
        service.addUsage(tenantId, userId, -5);
        verify(valueOps, never()).increment(anyString(), any(Long.class));
    }

    @Test
    @DisplayName("addUsage：Redis 异常 fail-open 并记录计数")
    void addUsage_redisError() {
        when(valueOps.increment(anyString(), any(Long.class))).thenThrow(new RuntimeException("conn refused"));

        service.addUsage(tenantId, userId, 10);

        verify(counter).increment();
    }

    @Test
    @DisplayName("getUsedSeconds：无记录 0 / 有记录解析 / 异常 0")
    void getUsedSeconds() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertThat(service.getUsedSeconds(tenantId, userId)).isZero();

        when(valueOps.get(anyString())).thenReturn("120");
        assertThat(service.getUsedSeconds(tenantId, userId)).isEqualTo(120);

        when(valueOps.get(anyString())).thenThrow(new RuntimeException("boom"));
        assertThat(service.getUsedSeconds(tenantId, userId)).isZero();
    }

    @Test
    @DisplayName("isExceeded：上限判定（30 分钟 = 1800 秒）")
    void isExceeded() {
        when(valueOps.get(anyString())).thenReturn("1799");
        assertThat(service.isExceeded(tenantId, userId)).isFalse();

        when(valueOps.get(anyString())).thenReturn("1800");
        assertThat(service.isExceeded(tenantId, userId)).isTrue();
    }

    @Test
    @DisplayName("isExceeded：管理员关闭限制（max<=0）恒 false")
    void isExceeded_disabled() {
        service = new UsageTimeLimitService(redisTemplate, 0, 300, meterRegistry);
        assertThat(service.isExceeded(tenantId, userId)).isFalse();
    }

    @Test
    @DisplayName("getRemainingSeconds：剩余 / 已超 0 / 关闭限制 MAX_VALUE")
    void getRemainingSeconds() {
        when(valueOps.get(anyString())).thenReturn("600");
        assertThat(service.getRemainingSeconds(tenantId, userId)).isEqualTo(1200);

        when(valueOps.get(anyString())).thenReturn("2000");
        assertThat(service.getRemainingSeconds(tenantId, userId)).isZero();

        service = new UsageTimeLimitService(redisTemplate, 0, 300, meterRegistry);
        assertThat(service.getRemainingSeconds(tenantId, userId)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("getMaxDailyMinutes：暴露配置值")
    void getMaxDailyMinutes() {
        assertThat(service.getMaxDailyMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("tryMarkWarned：剩余充足（> 300s）→ -1 且不占位")
    void tryMarkWarned_remainingSufficient() {
        when(valueOps.get(anyString())).thenReturn("600"); // remaining = 1800 - 600 = 1200

        assertThat(service.tryMarkWarned(tenantId, userId)).isEqualTo(-1);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("tryMarkWarned：进入预警窗且首次占位成功 → 返回剩余秒数")
    void tryMarkWarned_first() {
        when(valueOps.get(anyString())).thenReturn("1500"); // remaining = 300 = 阈值，边界含等于
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(service.tryMarkWarned(tenantId, userId)).isEqualTo(300);
    }

    @Test
    @DisplayName("tryMarkWarned：今日已提醒（占位失败）→ -1")
    void tryMarkWarned_alreadyWarned() {
        when(valueOps.get(anyString())).thenReturn("1500");
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.tryMarkWarned(tenantId, userId)).isEqualTo(-1);
    }

    @Test
    @DisplayName("tryMarkWarned：管理员关闭限制（max<=0）→ -1")
    void tryMarkWarned_disabled() {
        service = new UsageTimeLimitService(redisTemplate, 0, 300, meterRegistry);

        assertThat(service.tryMarkWarned(tenantId, userId)).isEqualTo(-1);
    }

    @Test
    @DisplayName("tryMarkWarned：预警开关关闭（warn<=0）→ -1")
    void tryMarkWarned_warnDisabled() {
        service = new UsageTimeLimitService(redisTemplate, 30, 0, meterRegistry);

        assertThat(service.tryMarkWarned(tenantId, userId)).isEqualTo(-1);
    }

    @Test
    @DisplayName("tryMarkWarned：Redis 异常 fail-open → -1 并计数")
    void tryMarkWarned_redisError() {
        when(valueOps.get(anyString())).thenReturn("1500");
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThat(service.tryMarkWarned(tenantId, userId)).isEqualTo(-1);
        verify(counter).increment();
    }
}
