package com.mindsafe.service.teacher;

import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.SysConfig;
import com.mindsafe.domain.entity.SlaEscalationLog;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.SlaEscalationLogMapper;
import com.mindsafe.domain.mapper.SysConfigMapper;
import com.mindsafe.service.alert.AlertService;
import com.mindsafe.service.alert.AlertService.AlertLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P-05 SLA 超时兜底扫描 单元测试
 * <p>
 * 使用真实 {@link AlertSlaPolicy}（纯函数），mock RiskEventMapper 与 AlertService。
 * 多实例语义（专题 F P0-5）：冷却记录存 Redis（SET NX EX），模拟两实例共享同一
 * Redis store 断言不双发；TTL 到期（键消失）后可再次告警。
 */
class SlaEscalationScannerTest {

    private RiskEventMapper riskEventMapper;
    private SlaEscalationLogMapper slaEscalationLogMapper;
    private SysConfigMapper sysConfigMapper;
    private AlertService alertService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SlaEscalationScanner scanner;

    /** 冷却键 in-memory 模拟（Redis 语义：setIfAbsent 原子占键；多实例共享同一 store） */
    private Map<String, String> cooldownStore;

    @BeforeEach
    void setUp() {
        riskEventMapper = mock(RiskEventMapper.class);
        slaEscalationLogMapper = mock(SlaEscalationLogMapper.class);
        sysConfigMapper = mock(SysConfigMapper.class);
        alertService = mock(AlertService.class);

        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cooldownStore = new ConcurrentHashMap<>();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> cooldownStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);

        scanner = new SlaEscalationScanner(riskEventMapper, new AlertSlaPolicy(), alertService,
                slaEscalationLogMapper, sysConfigMapper, redisTemplate, true, 30);
    }

    /** 共享同一 Redis（同一 cooldownStore）的第二个实例——多实例语义测试用 */
    private SlaEscalationScanner secondInstance() {
        return new SlaEscalationScanner(riskEventMapper, new AlertSlaPolicy(), alertService,
                slaEscalationLogMapper, sysConfigMapper, redisTemplate, true, 30);
    }

    private RiskEvent event(int riskLevel, String status, int ageMinutes) {
        RiskEvent e = new RiskEvent();
        e.setRiskEventId(UUID.randomUUID());
        e.setTenantId(UUID.randomUUID());
        e.setStudentUserId(UUID.randomUUID());
        e.setRiskType("suicide_ideation");
        e.setRiskLevel(riskLevel);
        e.setStatus(status);
        e.setCreatedAt(Instant.now().minus(ageMinutes, ChronoUnit.MINUTES));
        return e;
    }

    @Test
    @DisplayName("红色 open 超 5 分钟 → CRITICAL 升级告警")
    void redOpenOverdue_escalatesCritical() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "open", 6)));

        scanner.scan();

        verify(alertService).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    @Test
    @DisplayName("升级留痕：主键由代码生成 + stage/action 就位（IdType.INPUT 实体，缺则 INSERT NULL）")
    void recordEscalation_persistsWithGeneratedId() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "open", 6)));

        scanner.scan();

        ArgumentCaptor<SlaEscalationLog> captor = ArgumentCaptor.forClass(SlaEscalationLog.class);
        verify(slaEscalationLogMapper).insert(captor.capture());
        SlaEscalationLog log = captor.getValue();
        assertThat(log.getEscalationId()).isNotNull();
        assertThat(log.getRiskEventId()).isNotNull();
        assertThat(log.getStage()).isEqualTo("ack");
        assertThat(log.getAction()).isEqualTo(SlaEscalationLog.ACTION_NOTIFY_ESCALATE);
        assertThat(log.getEscalatedAt()).isNotNull();
    }

    @Test
    @DisplayName("留痕落库失败 → 不中断扫描（告警已发，台账缺失仅 WARN）")
    void recordEscalation_failureDoesNotBreakScan() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "open", 6)));
        when(slaEscalationLogMapper.insert(any(SlaEscalationLog.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(scanner::scan).doesNotThrowAnyException();

        // 告警链路不受留痕失败影响
        verify(alertService).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    @Test
    @DisplayName("红色 claimed 超时 → WARNING 提醒告警（认领未处理完）")
    void redClaimedOverdue_remindsWarning() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "claimed", 10)));

        scanner.scan();

        verify(alertService).sendAlert(eq(AlertLevel.WARNING), anyString(), anyString());
    }

    @Test
    @DisplayName("红色 open 未超 5 分钟 → 不告警")
    void redOpenWithinSla_noAlert() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of(event(3, "open", 3)));

        scanner.scan();

        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("冷却期内重复扫描同一事件 → 只告警一次（Redis 冷却键）")
    void dedupWithinCooldown_alertsOnce() {
        List<RiskEvent> same = List.of(event(3, "open", 6));
        when(riskEventMapper.selectList(any())).thenReturn(same);

        scanner.scan();
        scanner.scan();

        verify(alertService, times(1)).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    // ===== 专题 F P0-5：多实例语义 =====

    @Test
    @DisplayName("多实例：两实例互不知晓，共享 Redis 冷却键 → 只告警一次（不双发）")
    void multiInstance_sharedCooldown_alertsOnce() {
        List<RiskEvent> same = List.of(event(3, "open", 6));
        when(riskEventMapper.selectList(any())).thenReturn(same);
        SlaEscalationScanner instanceB = secondInstance();

        scanner.scan();   // 实例 A：占冷却键成功 → 告警
        instanceB.scan(); // 实例 B：读到共享键存在 → 冷却中跳过

        verify(alertService, times(1)).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    @Test
    @DisplayName("多实例：A 已告警后，B 与 A 各自再扫描均不再告警（键在冷却期内）")
    void multiInstance_subsequentScansSuppressed() {
        List<RiskEvent> same = List.of(event(3, "open", 6));
        when(riskEventMapper.selectList(any())).thenReturn(same);
        SlaEscalationScanner instanceB = secondInstance();

        scanner.scan();
        instanceB.scan();
        scanner.scan();
        instanceB.scan();

        verify(alertService, times(1)).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    @Test
    @DisplayName("冷却 TTL 到期（键消失）→ 允许再次告警（等价原 Duration 冷却判定）")
    void cooldownExpiry_allowsReAlert() {
        List<RiskEvent> same = List.of(event(3, "open", 6));
        when(riskEventMapper.selectList(any())).thenReturn(same);

        scanner.scan(); // 第 1 次告警，占键（TTL=冷却期）
        cooldownStore.clear(); // 模拟 TTL 到期键自动消失
        scanner.scan(); // 冷却结束 → 第 2 次告警

        verify(alertService, times(2)).sendAlert(eq(AlertLevel.CRITICAL), anyString(), anyString());
    }

    @Test
    @DisplayName("sys_config DB 值覆盖 yml 默认（HOT 键 false 暂停扫描，AUDIT-DEEP-003）")
    void sysConfigOverridesYmlEnabled() {
        SysConfig config = new SysConfig();
        config.setConfigKey("mindsafe.security.sla-escalation.enabled");
        config.setValue("false");
        when(sysConfigMapper.selectOne(any())).thenReturn(config);

        scanner.scan();

        verifyNoInteractions(riskEventMapper);
        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("isEnabled 短 TTL 缓存：缓存窗口内多次扫描只查一次 sys_config（P0-5）")
    void enabledCache_hitsWithinTtl() {
        when(riskEventMapper.selectList(any())).thenReturn(List.of());
        when(sysConfigMapper.selectOne(any())).thenReturn(null); // 键缺失 → 回落 yml=true

        scanner.scan();
        scanner.scan();

        // 第一次扫描查库填充缓存，第二次命中缓存不再查库
        verify(sysConfigMapper, times(1)).selectOne(any());
    }

    @Test
    @DisplayName("sys_config 键缺失 → 回落 yml 默认（fail-open 不阻断扫描）")
    void sysConfigMissingFallsBackToYml() {
        when(sysConfigMapper.selectOne(any())).thenReturn(null);

        scanner.scan(); // 构造 enabled=true，应正常扫描

        verify(riskEventMapper).selectList(any());
    }

    @Test
    @DisplayName("关闭时 enabled=false → 不扫描不告警")
    void disabled_skipsScan() {
        SlaEscalationScanner disabled =
                new SlaEscalationScanner(riskEventMapper, new AlertSlaPolicy(), alertService,
                        slaEscalationLogMapper, sysConfigMapper, redisTemplate, false, 30);

        disabled.scan();

        verifyNoInteractions(riskEventMapper);
        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("单次异常不抛出（定时任务不中断）")
    void mapperException_swallowed() {
        when(riskEventMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        scanner.scan(); // 不应抛出

        verify(alertService, never()).sendAlert(any(), anyString(), anyString());
    }
}
