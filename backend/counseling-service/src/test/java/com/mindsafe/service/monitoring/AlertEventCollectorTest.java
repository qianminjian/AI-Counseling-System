package com.mindsafe.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.AlertEvent;
import com.mindsafe.domain.mapper.AlertEventMapper;
import com.mindsafe.service.alert.AlertService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警事件采集器单元测试（OPS-MON-008，AC-10）
 * 覆盖：首采插入/重复采集去重/firing→resolved 流转/业务告警落库/AlertManager 不可达降级
 */
class AlertEventCollectorTest {

    /** 纯单测环境无 MyBatis 启动：手动初始化实体元数据缓存（供 LambdaQueryWrapper 拼参） */
    private static void initMybatisMeta(Class<?> entityClass) {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, entityClass);
    }

    private HttpServer server;
    private int port;
    private AlertEventMapper mapper;
    private AlertEventCollector collector;

    /** AlertManager /api/v2/alerts 响应体（可切换） */
    private volatile String alertsBody = "[]";

    @BeforeEach
    void setUp() throws Exception {
        initMybatisMeta(AlertEvent.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/alerts", exchange -> {
            byte[] bytes = alertsBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();

        mapper = mock(AlertEventMapper.class);
        collector = new AlertEventCollector("http://127.0.0.1:" + port, mapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String alertJson(String fingerprint, String state, String alertname) {
        return String.format("""
                [{"status":{"state":"%s"},"labels":{"alertname":"%s","severity":"warning"},
                  "annotations":{"summary":"TTS 主引擎（CosyVoice）持续降级","description":"detail"},
                  "startsAt":"2026-08-09T10:00:00Z","endsAt":"0001-01-01T00:00:00Z",
                  "fingerprint":"%s"}]
                """, state, alertname, fingerprint);
    }

    @Test
    @DisplayName("首次采集 → 新告警插入（source=alertmanager，firing）")
    void firstCollectInsertsAlerts() {
        alertsBody = alertJson("fp-tts-1", "active", "TtsPrimaryEngineDegraded");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        collector.collect();

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(mapper).insert(captor.capture());
        AlertEvent event = captor.getValue();
        assertThat(event.getSource()).isEqualTo(AlertEvent.SOURCE_ALERTMANAGER);
        assertThat(event.getFingerprint()).isEqualTo("fp-tts-1");
        assertThat(event.getRuleName()).isEqualTo("TtsPrimaryEngineDegraded");
        assertThat(event.getSeverity()).isEqualTo("warning");
        assertThat(event.getStatus()).isEqualTo(AlertEvent.STATUS_FIRING);
        assertThat(event.getSummary()).isEqualTo("TTS 主引擎（CosyVoice）持续降级");
        assertThat(event.getFiredAt()).isEqualTo(Instant.parse("2026-08-09T10:00:00Z"));
    }

    @Test
    @DisplayName("重复采集（同 fingerprint 已在库 firing）→ 不重复插入（upsert 去重）")
    void duplicateCollectSkipsInsert() {
        alertsBody = alertJson("fp-tts-1", "active", "TtsPrimaryEngineDegraded");
        AlertEvent existing = new AlertEvent();
        existing.setEventId(UUID.randomUUID());
        existing.setSource(AlertEvent.SOURCE_ALERTMANAGER);
        existing.setFingerprint("fp-tts-1");
        existing.setStatus(AlertEvent.STATUS_FIRING);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        collector.collect();

        verify(mapper, never()).insert(any(AlertEvent.class));
    }

    @Test
    @DisplayName("采集列表缺失 firing 告警（且 firedAt 早于 2 采集周期）→ 标记 resolved")
    void missingAlertMarkedResolved() {
        alertsBody = "[]";
        AlertEvent firing = new AlertEvent();
        firing.setEventId(UUID.randomUUID());
        firing.setSource(AlertEvent.SOURCE_ALERTMANAGER);
        firing.setFingerprint("fp-gone");
        firing.setStatus(AlertEvent.STATUS_FIRING);
        firing.setFiredAt(Instant.now().minus(java.time.Duration.ofMinutes(5)));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(firing));

        collector.collect();

        verify(mapper).updateById(firing);
        assertThat(firing.getStatus()).isEqualTo(AlertEvent.STATUS_RESOLVED);
        assertThat(firing.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("采集列表缺失但 firedAt 太近（AlertManager 重启/空列表）→ 不流转（M3 防抖）")
    void missingRecentAlertNotMarkedResolved() {
        alertsBody = "[]";
        AlertEvent recent = new AlertEvent();
        recent.setEventId(UUID.randomUUID());
        recent.setSource(AlertEvent.SOURCE_ALERTMANAGER);
        recent.setFingerprint("fp-recent");
        recent.setStatus(AlertEvent.STATUS_FIRING);
        recent.setFiredAt(Instant.now());
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(recent));

        collector.collect();

        verify(mapper, never()).updateById(any(AlertEvent.class));
        assertThat(recent.getStatus()).isEqualTo(AlertEvent.STATUS_FIRING);
    }

    @Test
    @DisplayName("已 resolved 告警复燃（同 fingerprint 再次 active）→ 置回 firing 并刷新触发时间（M1）")
    void reflameAlertBackToFiring() {
        alertsBody = alertJson("fp-tts-1", "active", "TtsPrimaryEngineDegraded");
        AlertEvent resolved = new AlertEvent();
        resolved.setEventId(UUID.randomUUID());
        resolved.setSource(AlertEvent.SOURCE_ALERTMANAGER);
        resolved.setFingerprint("fp-tts-1");
        resolved.setStatus(AlertEvent.STATUS_RESOLVED);
        resolved.setResolvedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(resolved);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        collector.collect();

        verify(mapper).updateById(resolved);
        assertThat(resolved.getStatus()).isEqualTo(AlertEvent.STATUS_FIRING);
        assertThat(resolved.getFiredAt()).isEqualTo(Instant.parse("2026-08-09T10:00:00Z"));
        assertThat(resolved.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("业务告警落库（AlertService 发出同步写，source=alertservice）")
    void recordBusinessAlert() {
        collector.record(AlertService.AlertLevel.CRITICAL, "逾期预警升级", "S0 超时未认领");

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(mapper).insert(captor.capture());
        AlertEvent event = captor.getValue();
        assertThat(event.getSource()).isEqualTo(AlertEvent.SOURCE_ALERTSERVICE);
        assertThat(event.getRuleName()).isEqualTo("逾期预警升级");
        assertThat(event.getSeverity()).isEqualTo("CRITICAL");
        assertThat(event.getStatus()).isEqualTo(AlertEvent.STATUS_FIRING);
        assertThat(event.getFiredAt()).isNotNull();
    }

    @Test
    @DisplayName("AlertManager 不可达 → 不崩溃（采集失败仅告警日志）")
    void alertmanagerUnreachableDegradesGracefully() {
        server.stop(0);
        assertThatCode(collector::collect).doesNotThrowAnyException();
        verify(mapper, never()).insert(any(AlertEvent.class));
        verify(mapper, times(0)).updateById(any(AlertEvent.class));
    }

    @Test
    @DisplayName("30 天清理：仅删除 resolved 超期（status + resolved_at 条件，缺口 3，AC-10）")
    void cleanupDeletesExpired() {
        when(mapper.delete(any(Wrapper.class))).thenReturn(7);
        collector.cleanup();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AlertEvent>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).delete(captor.capture());
        LambdaQueryWrapper<AlertEvent> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("status").contains("resolved_at");
        assertThat(wrapper.getParamNameValuePairs().values())
                .anyMatch(v -> AlertEvent.STATUS_RESOLVED.equals(v));
    }
}
