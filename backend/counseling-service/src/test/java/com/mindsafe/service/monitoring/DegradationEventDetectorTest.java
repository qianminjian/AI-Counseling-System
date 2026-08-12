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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 降级事件检测器单元测试（OPS-MON-007，AC-9）
 * 覆盖：auto 事件/恢复事件/防抖/manual 覆盖跳过/SETNX 锁/Prometheus 不可达降级/
 * 多实例语义（专题 F P0-4：防抖状态存 Redis 共享 + DB dedup_key 幂等兜底）
 */
class DegradationEventDetectorTest {

    private HttpServer server;
    private int port;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private DegradationEventMapper mapper;
    private DegradationEventDetector detector;

    /** 防抖状态键 in-memory 模拟（Redis 语义：get 读、set 写；多实例测试共享同一 store） */
    private Map<String, String> stateStore;

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

        // 防抖状态键（STATE_KEY_PREFIX）走 in-memory store：set 写、get 读（模拟多实例共享同一 Redis）
        stateStore = new ConcurrentHashMap<>();
        when(valueOps.get(anyString())).thenAnswer(inv -> stateStore.get(inv.getArgument(0)));
        doAnswer(inv -> {
            stateStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        mapper = mock(DegradationEventMapper.class);
        detector = new DegradationEventDetector("http://127.0.0.1:" + port, redisTemplate, mapper, Duration.ofHours(24));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 共享同一 Redis 的第二个实例（多实例语义测试用） */
    private DegradationEventDetector secondInstance() {
        return new DegradationEventDetector("http://127.0.0.1:" + port, redisTemplate, mapper, Duration.ofHours(24));
    }

    @Test
    @DisplayName("降级发生 → tts/llm 各写一条 auto 事件（from/to/trigger/dedup_key 正确）")
    void degradedWritesAutoEvents() {
        degradedMode = true;
        detector.scan();

        verify(mapper, times(2)).insertOnConflictDoNothing(any(DegradationEvent.class));
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(mapper, times(2)).insertOnConflictDoNothing(captor.capture());
        List<DegradationEvent> events = captor.getAllValues();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getPoint()).isEqualTo("tts");
            assertThat(e.getFromState()).isEqualTo("cosyvoice");
            assertThat(e.getToState()).isEqualTo("edge_tts");
            assertThat(e.getTriggerType()).isEqualTo(DegradationEvent.TRIGGER_AUTO);
            assertThat(e.getOccurredAt()).isNotNull();
            // V48 dedup_key：trigger:point:from->to:时间桶
            assertThat(e.getDedupKey()).startsWith("auto:tts:cosyvoice->edge_tts:");
        });
        assertThat(events).anySatisfy(e -> assertThat(e.getPoint()).isEqualTo("llm"));
    }

    @Test
    @DisplayName("持续降级（连续轮询同态）→ 不重复写事件（Redis 防抖状态 last_value）")
    void sustainedDegradationDoesNotDuplicate() {
        degradedMode = true;
        detector.scan();
        detector.scan();
        detector.scan();

        verify(mapper, times(2)).insertOnConflictDoNothing(any(DegradationEvent.class));
    }

    @Test
    @DisplayName("降级恢复 → 写恢复事件（from=fallback to=primary）")
    void recoveryWritesRecoveryEvent() {
        degradedMode = true;
        detector.scan();
        degradedMode = false;
        detector.scan();

        // tts/llm 各一条降级 + 各一条恢复 = 4 条
        verify(mapper, times(4)).insertOnConflictDoNothing(any(DegradationEvent.class));
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(mapper, times(4)).insertOnConflictDoNothing(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(e -> {
            // 主键由代码生成（IdType.INPUT 实体，缺则 INSERT NULL 违反 NOT NULL——2026-08-10 修复）
            assertThat(e.getEventId()).isNotNull();
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
        verify(mapper, times(1)).insertOnConflictDoNothing(any(DegradationEvent.class));
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(mapper).insertOnConflictDoNothing(captor.capture());
        assertThat(captor.getValue().getPoint()).isEqualTo("llm");
    }

    @Test
    @DisplayName("分布式锁被占 → 跳过本次扫描（SETNX 防多实例重复）")
    void lockHeldSkipsScan() {
        degradedMode = true;
        when(valueOps.setIfAbsent(eq(DegradationEventDetector.SCAN_LOCK_KEY), anyString(), any(Duration.class)))
                .thenReturn(false);

        detector.scan();

        verify(mapper, never()).insertOnConflictDoNothing(any(DegradationEvent.class));
    }

    @Test
    @DisplayName("Prometheus 不可达 → 不写事件、不抛异常（降级检测不影响业务）")
    void prometheusUnreachableDegradesGracefully() {
        server.stop(0);
        degradedMode = true;

        assertThatCode(detector::scan).doesNotThrowAnyException();
        verify(mapper, never()).insertOnConflictDoNothing(any(DegradationEvent.class));
    }

    // ===== 专题 F P0-4：多实例语义 =====

    @Test
    @DisplayName("多实例：实例 B 共享实例 A 的 Redis 防抖状态，窗口内不重复写事件")
    void multiInstance_sharedDebounceState_noDuplicate() {
        degradedMode = true;
        DegradationEventDetector instanceB = secondInstance();

        detector.scan();  // 实例 A：写 tts/llm 各 1 条 + state=1
        instanceB.scan(); // 实例 B：读到共享 state=1 → 同态跳过，不写

        verify(mapper, times(2)).insertOnConflictDoNothing(any(DegradationEvent.class));
    }

    @Test
    @DisplayName("多实例：实例 B 读到 A 写入的恢复状态后不重复写恢复事件")
    void multiInstance_recoveryStateShared() {
        degradedMode = true;
        DegradationEventDetector instanceB = secondInstance();

        detector.scan();   // A：降级 → tts/llm 各 1 条，state=1
        degradedMode = false;
        detector.scan();   // A：恢复 → tts/llm 各 1 条，state=0
        instanceB.scan();  // B：state=0 + 正常 → 同态跳过

        verify(mapper, times(4)).insertOnConflictDoNothing(any(DegradationEvent.class));
    }

    @Test
    @DisplayName("多实例：B 在 A 恢复前扫描到降级态，恢复后 A/B 都不重复写")
    void multiInstance_degradedThenRecovered() {
        degradedMode = true;
        DegradationEventDetector instanceB = secondInstance();

        detector.scan();   // A：降级 → 2 条，state=1
        instanceB.scan();  // B：state=1 → 跳过
        degradedMode = false;
        detector.scan();   // A：恢复 → 2 条，state=0
        instanceB.scan();  // B：state=0 → 跳过

        verify(mapper, times(4)).insertOnConflictDoNothing(any(DegradationEvent.class));
    }

    @Test
    @DisplayName("DB 幂等兜底：同窗口重复写被 ON CONFLICT 吞掉（返回 0）时状态机仍推进、不抛异常")
    void dbIdempotency_conflictSwallowed_stateStillAdvances() {
        degradedMode = true;
        when(mapper.insertOnConflictDoNothing(any(DegradationEvent.class))).thenReturn(0);

        assertThatCode(detector::scan).doesNotThrowAnyException();
        assertThatCode(detector::scan).doesNotThrowAnyException();

        // 每次扫描都尝试写入（由 DB 唯一键裁决），状态机正常推进
        verify(mapper, times(2)).insertOnConflictDoNothing(any(DegradationEvent.class));
        // 状态已写 Redis（state=1）→ 后续扫描读到降级态不再尝试重复写业务事件
        verify(valueOps, atLeastOnce()).set(eq("mindsafe:degradation:state:tts"), eq("1"), any(Duration.class));
        assertThat(stateStore).containsEntry("mindsafe:degradation:state:tts", "1");
    }

    @Test
    @DisplayName("dedup_key 格式：trigger:point:from->to:时间桶（同窗口同桶、跨窗口翻页）")
    void dedupKeyFormat() {
        Instant t = Instant.ofEpochSecond(1000); // 窗口=3600s → 桶 0
        assertThat(DegradationEventDetector.buildDedupKey("auto", "tts", "cosyvoice", "edge_tts", t, 3600))
                .isEqualTo("auto:tts:cosyvoice->edge_tts:0");
        // 跨窗口（7200/3600=桶 2）→ 不同桶，同一转换可再次落库（长周期多次留痕）
        assertThat(DegradationEventDetector.buildDedupKey("auto", "tts", "cosyvoice", "edge_tts", Instant.ofEpochSecond(7200), 3600))
                .isEqualTo("auto:tts:cosyvoice->edge_tts:2");
    }
}
