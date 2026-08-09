package com.mindsafe.service.monitoring;

import com.mindsafe.domain.entity.DegradationEvent;
import com.mindsafe.domain.mapper.DegradationEventMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 降级事件检测器单元测试（OPS-MON-007，AC-9）
 * 覆盖：auto 事件/恢复事件/防抖/manual 覆盖跳过/SETNX 锁/Prometheus 不可达降级
 */
class DegradationEventDetectorTest {

    private HttpServer server;
    private int port;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private DegradationEventMapper mapper;
    private DegradationEventDetector detector;

    /** Prometheus 响应模式：true=降级中（result 非空），false=正常（空 result） */
    private volatile boolean degradedMode;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            String body = degradedMode
                    ? "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1,\"1\"]}]}}"
                    : "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();

        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        mapper = mock(DegradationEventMapper.class);
        detector = new DegradationEventDetector("http://127.0.0.1:" + port, redisTemplate, mapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("降级发生 → tts/llm 各写一条 auto 事件（from/to/trigger 正确）")
    void degradedWritesAutoEvents() {
        degradedMode = true;
        detector.scan();

        verify(mapper, times(2)).insert(any(DegradationEvent.class));
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(mapper, times(2)).insert(captor.capture());
        List<DegradationEvent> events = captor.getAllValues();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getPoint()).isEqualTo("tts");
            assertThat(e.getFromState()).isEqualTo("cosyvoice");
            assertThat(e.getToState()).isEqualTo("edge_tts");
            assertThat(e.getTriggerType()).isEqualTo(DegradationEvent.TRIGGER_AUTO);
            assertThat(e.getOccurredAt()).isNotNull();
        });
        assertThat(events).anySatisfy(e -> assertThat(e.getPoint()).isEqualTo("llm"));
    }

    @Test
    @DisplayName("持续降级（连续轮询同态）→ 不重复写事件（last_value 防抖）")
    void sustainedDegradationDoesNotDuplicate() {
        degradedMode = true;
        detector.scan();
        detector.scan();
        detector.scan();

        verify(mapper, times(2)).insert(any(DegradationEvent.class));
    }

    @Test
    @DisplayName("降级恢复 → 写恢复事件（from=fallback to=primary）")
    void recoveryWritesRecoveryEvent() {
        degradedMode = true;
        detector.scan();
        degradedMode = false;
        detector.scan();

        // tts/llm 各一条降级 + 各一条恢复 = 4 条
        verify(mapper, times(4)).insert(any(DegradationEvent.class));
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(mapper, times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(e -> {
            assertThat(e.getPoint()).isEqualTo("tts");
            assertThat(e.getFromState()).isEqualTo("edge_tts");
            assertThat(e.getToState()).isEqualTo("cosyvoice");
            assertThat(e.getDetail()).isEqualTo("降级恢复");
        });
    }

    @Test
    @DisplayName("手动覆盖期间 → 不写 auto 事件（避免与 manual 双写）")
    void manualOverrideSkipsAutoEvent() {
        degradedMode = true;
        when(redisTemplate.hasKey("mindsafe:degradation:override:tts")).thenReturn(true);

        detector.scan();

        // tts 被跳过，仅 llm 写事件
        verify(mapper, times(1)).insert(any(DegradationEvent.class));
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getPoint()).isEqualTo("llm");
    }

    @Test
    @DisplayName("分布式锁被占 → 跳过本次扫描（SETNX 防多实例重复）")
    void lockHeldSkipsScan() {
        degradedMode = true;
        when(valueOps.setIfAbsent(eq(DegradationEventDetector.SCAN_LOCK_KEY), anyString(), any(Duration.class)))
                .thenReturn(false);

        detector.scan();

        verify(mapper, never()).insert(any(DegradationEvent.class));
    }

    @Test
    @DisplayName("Prometheus 不可达 → 不写事件、不抛异常（降级检测不影响业务）")
    void prometheusUnreachableDegradesGracefully() {
        server.stop(0);
        degradedMode = true;

        assertThatCode(detector::scan).doesNotThrowAnyException();
        verify(mapper, never()).insert(any(DegradationEvent.class));
    }
}
