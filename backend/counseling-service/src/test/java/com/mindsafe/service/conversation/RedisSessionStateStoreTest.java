package com.mindsafe.service.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * RedisSessionStateStore 单元测试（P0-1 审计修复：会话状态 Redis 持久化）
 * <p>
 * 覆盖：save（TTL 2h + JSON 序列化 + 序列化/写入失败降级）、
 * get（不存在返回 null + 正常反序列化 + 读取失败降级）、
 * remove / exists 及各自的异常降级路径。
 * <p>
 * ARCH-010 P2-4：key 含租户段（session:state:{tenantId}:{sessionId}），
 * 旧格式（session:state:{sessionId}）读回查 + 双写迁移，TTL 自然过期清理。
 */
@ExtendWith(MockitoExtension.class)
class RedisSessionStateStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private RedisSessionStateStore store;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisSessionStateStore(redisTemplate, objectMapper, new NudgeProperties());
    }

    private SessionState newState() {
        return new SessionState(sessionId, UUID.randomUUID(), UUID.randomUUID(),
                "sad", "voice", "M", 0.7, 5);
    }

    @Test
    @DisplayName("save：写入 session:state:{tenantId}:{id}，JSON 可反序列化，TTL 2 小时")
    void saveWritesJsonWithTtl() {
        SessionState state = newState();
        state.setTurnCount(6);

        store.save(tenantId, sessionId, state);

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), jsonCaptor.capture(), eq(Duration.ofHours(2)));
        assertThat(keyCaptor.getValue()).isEqualTo("session:state:" + tenantId + ":" + sessionId);
        assertThatCode(() -> {
            SessionState restored = objectMapper.readValue(jsonCaptor.getValue(), SessionState.class);
            assertThat(restored.getSessionId()).isEqualTo(sessionId);
            assertThat(restored.getTurnCount()).isEqualTo(6);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("save：序列化失败 → 记日志不抛出，不写 Redis")
    void saveSerializationFailure() throws Exception {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {});
        RedisSessionStateStore failingStore = new RedisSessionStateStore(redisTemplate, failingMapper, new NudgeProperties());

        assertThatCode(() -> failingStore.save(tenantId, sessionId, newState())).doesNotThrowAnyException();
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("save：Redis 写入异常 → 记日志不抛出")
    void saveRedisFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> store.save(tenantId, sessionId, newState())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("get：key 不存在返回 null（先查新格式）")
    void getMissingReturnsNull() {
        when(valueOperations.get("session:state:" + tenantId + ":" + sessionId)).thenReturn(null);
        when(valueOperations.get("session:state:" + sessionId)).thenReturn(null);

        assertThat(store.get(tenantId, sessionId)).isNull();
    }

    @Test
    @DisplayName("get：新格式命中直接返回，不回查旧格式")
    void getPrefersNewFormatKey() throws Exception {
        SessionState state = newState();
        state.setSessionSummary("新格式命中");
        String json = objectMapper.writeValueAsString(state);
        when(valueOperations.get("session:state:" + tenantId + ":" + sessionId)).thenReturn(json);

        SessionState restored = store.get(tenantId, sessionId);

        assertThat(restored).isNotNull();
        assertThat(restored.getSessionSummary()).isEqualTo("新格式命中");
        verify(valueOperations).get("session:state:" + tenantId + ":" + sessionId);
        verify(valueOperations, never()).get("session:state:" + sessionId);
    }

    @Test
    @DisplayName("get：新格式未命中回查旧格式，命中后双写迁移到新 key（旧 key 留 TTL 自然过期）")
    void getMigratesLegacyKey() throws Exception {
        SessionState state = newState();
        state.setSessionSummary("旧格式存量数据");
        String json = objectMapper.writeValueAsString(state);
        when(valueOperations.get("session:state:" + tenantId + ":" + sessionId)).thenReturn(null);
        when(valueOperations.get("session:state:" + sessionId)).thenReturn(json);

        SessionState restored = store.get(tenantId, sessionId);

        assertThat(restored).isNotNull();
        assertThat(restored.getSessionSummary()).isEqualTo("旧格式存量数据");
        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), jsonCaptor.capture(), eq(Duration.ofHours(2)));
        assertThat(keyCaptor.getValue()).isEqualTo("session:state:" + tenantId + ":" + sessionId);
        assertThat(jsonCaptor.getValue()).isEqualTo(json);
    }

    @Test
    @DisplayName("get：正常反序列化 SessionState（新格式）")
    void getRoundTrip() throws Exception {
        SessionState state = newState();
        state.setSessionSummary("学生聊了考试压力");
        state.setLastSummaryTurn(4);
        String json = objectMapper.writeValueAsString(state);
        when(valueOperations.get("session:state:" + tenantId + ":" + sessionId)).thenReturn(json);

        SessionState restored = store.get(tenantId, sessionId);

        assertThat(restored).isNotNull();
        assertThat(restored.getSessionId()).isEqualTo(sessionId);
        assertThat(restored.getSessionSummary()).isEqualTo("学生聊了考试压力");
        assertThat(restored.getLastSummaryTurn()).isEqualTo(4);
    }

    @Test
    @DisplayName("get：非法 JSON → 返回 null 降级")
    void getInvalidJsonReturnsNull() {
        when(valueOperations.get("session:state:" + tenantId + ":" + sessionId)).thenReturn("{not-valid-json");

        assertThat(store.get(tenantId, sessionId)).isNull();
    }

    @Test
    @DisplayName("get：Redis 读取异常 → 返回 null 降级")
    void getRedisFailureReturnsNull() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(store.get(tenantId, sessionId)).isNull();
    }

    @Test
    @DisplayName("remove：删除新格式 + 旧格式两个 key（旧格式无租户段，双删清理）")
    void removeDeletesKey() {
        when(redisTemplate.delete("session:state:" + tenantId + ":" + sessionId)).thenReturn(true);
        when(redisTemplate.delete("session:state:" + sessionId)).thenReturn(true);

        store.remove(tenantId, sessionId);

        verify(redisTemplate).delete("session:state:" + tenantId + ":" + sessionId);
        verify(redisTemplate).delete("session:state:" + sessionId);
    }

    @Test
    @DisplayName("remove：Redis 删除异常 → 记日志不抛出")
    void removeFailureSwallowed() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> store.remove(tenantId, sessionId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("exists：key 存在返回 true，不存在/null 返回 false（新格式）")
    void existsVariants() {
        when(redisTemplate.hasKey("session:state:" + tenantId + ":" + sessionId)).thenReturn(true);
        assertThat(store.exists(tenantId, sessionId)).isTrue();

        when(redisTemplate.hasKey("session:state:" + tenantId + ":" + sessionId)).thenReturn(null);
        assertThat(store.exists(tenantId, sessionId)).isFalse();
    }

    @Test
    @DisplayName("exists：Redis 异常 → 返回 false 降级")
    void existsFailureReturnsFalse() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(store.exists(tenantId, sessionId)).isFalse();
    }

    @Test
    @DisplayName("tryNudge：Lua 返回 1 → 放行（已原子计数+时间戳）")
    void tryNudgeAllowsWhenScriptReturnsOne() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(store.tryNudge(tenantId, sessionId)).isTrue();
    }

    @Test
    @DisplayName("tryNudge：Lua 返回 0 → 拦截（护栏：次数超限/间隔不足）")
    void tryNudgeBlocksWhenScriptReturnsZero() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(0L);

        assertThat(store.tryNudge(tenantId, sessionId)).isFalse();
    }

    @Test
    @DisplayName("tryNudge：阈值取自 NudgeProperties 配置（Lua ARGV 注入，与快照同源）")
    void tryNudgeUsesConfiguredThresholds() {
        // 默认值契约：maxCount=2 / minIntervalSeconds=20（与 yml mindsafe.conversation.nudge 默认一致）
        assertThat(new NudgeProperties().getMaxCount()).isEqualTo(2);
        assertThat(new NudgeProperties().getMinIntervalSeconds()).isEqualTo(20L);

        NudgeProperties props = new NudgeProperties();
        props.setMaxCount(5);
        props.setMinIntervalSeconds(60);
        RedisSessionStateStore configuredStore = new RedisSessionStateStore(redisTemplate, objectMapper, props);
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(configuredStore.tryNudge(tenantId, sessionId)).isTrue();

        // ARGV 顺序：now / ttl / maxCount / minIntervalSeconds（Lua 内 ARGV[3]/ARGV[4] 取阈值）
        org.mockito.ArgumentCaptor<Object[]> argvCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(), anyList(), argvCaptor.capture());
        assertThat(argvCaptor.getValue()[2]).isEqualTo("5");
        assertThat(argvCaptor.getValue()[3]).isEqualTo("60");
    }

    @Test
    @DisplayName("tryNudge：Redis 异常 → 保守拦截 false（不双发暖场）")
    void tryNudgeFailureConservativelyBlocks() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(store.tryNudge(tenantId, sessionId)).isFalse();
    }

    @Test
    @DisplayName("resetNudgeCounter：删除计数键（保留 :at 时间戳键）")
    void resetNudgeCounterDeletesCountKey() {
        when(redisTemplate.delete("session:nudge:" + tenantId + ":" + sessionId)).thenReturn(true);

        store.resetNudgeCounter(tenantId, sessionId);

        verify(redisTemplate).delete("session:nudge:" + tenantId + ":" + sessionId);
    }

    @Test
    @DisplayName("resetNudgeCounter：Redis 异常 → 记日志不抛出")
    void resetNudgeCounterFailureSwallowed() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> store.resetNudgeCounter(tenantId, sessionId)).doesNotThrowAnyException();
    }

    // ===== BA-09：nudge 真值读接口（预判/展示走 Redis 独立键，Lua 原子裁决不受影响） =====

    @Test
    @DisplayName("getNudgeCount：键不存在 → 0")
    void getNudgeCountMissingReturnsZero() {
        when(valueOperations.get("session:nudge:" + tenantId + ":" + sessionId)).thenReturn(null);

        assertThat(store.getNudgeCount(tenantId, sessionId)).isZero();
    }

    @Test
    @DisplayName("getNudgeCount：键存在 → 解析计数真值")
    void getNudgeCountParsesValue() {
        when(valueOperations.get("session:nudge:" + tenantId + ":" + sessionId)).thenReturn("3");

        assertThat(store.getNudgeCount(tenantId, sessionId)).isEqualTo(3);
    }

    @Test
    @DisplayName("getNudgeCount：Redis 异常 → 0 降级（预判宽松，Lua 最终裁决）")
    void getNudgeCountFailureReturnsZero() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(store.getNudgeCount(tenantId, sessionId)).isZero();
    }

    @Test
    @DisplayName("getLastNudgeAt：键不存在 → null")
    void getLastNudgeAtMissingReturnsNull() {
        when(valueOperations.get("session:nudge:" + tenantId + ":" + sessionId + ":at")).thenReturn(null);

        assertThat(store.getLastNudgeAt(tenantId, sessionId)).isNull();
    }

    @Test
    @DisplayName("getLastNudgeAt：键存在 → epoch 秒转 Instant")
    void getLastNudgeAtParsesEpoch() {
        long epoch = 1750000000L;
        when(valueOperations.get("session:nudge:" + tenantId + ":" + sessionId + ":at"))
                .thenReturn(String.valueOf(epoch));

        assertThat(store.getLastNudgeAt(tenantId, sessionId)).isEqualTo(Instant.ofEpochSecond(epoch));
    }

    @Test
    @DisplayName("getLastNudgeAt：Redis 异常 → null 降级")
    void getLastNudgeAtFailureReturnsNull() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(store.getLastNudgeAt(tenantId, sessionId)).isNull();
    }

    @Test
    @DisplayName("tryNudge 并发：Lua 原子语义下放行恰为 maxCount 次（多实例并发计数一致，BA-09）")
    void tryNudgeConcurrentAllowsExactlyMaxCount() throws Exception {
        // 隔离间隔变量（minIntervalSeconds=0）：并发测试聚焦计数上限原子一致性
        NudgeProperties zeroInterval = new NudgeProperties();
        zeroInterval.setMinIntervalSeconds(0);
        RedisSessionStateStore concurrentStore = new RedisSessionStateStore(redisTemplate, objectMapper, zeroInterval);
        // 用内存 Map 模拟 Lua 脚本原子语义（synchronized 模拟 Redis 单线程执行），
        // 验证并发调用方 + store 传参下：计数不超上限、放行数恰好 = maxCount（默认 2）
        // 注意：Mockito 将 varargs 实参展开存储（getArguments() = {script, list, a, b, c, d}），
        //       ARGV 需从原始打包数组取（getRawArguments()[2]），否则 getArgument(2) 是 String 会 CCE
        Map<String, String> fakeRedis = new ConcurrentHashMap<>();
        when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> keys = inv.getArgument(1);
                    Object[] argv = (Object[]) inv.getRawArguments()[2];
                    long now = Long.parseLong((String) argv[0]);
                    int maxCount = Integer.parseInt((String) argv[2]);
                    long minInterval = Long.parseLong((String) argv[3]);
                    synchronized (fakeRedis) {
                        String countVal = fakeRedis.get(keys.get(0));
                        int count = countVal == null ? 0 : Integer.parseInt(countVal);
                        if (count >= maxCount) return 0L;
                        String lastVal = fakeRedis.get(keys.get(1));
                        if (lastVal != null && now - Long.parseLong(lastVal) < minInterval) return 0L;
                        fakeRedis.put(keys.get(0), String.valueOf(count + 1));
                        fakeRedis.put(keys.get(1), String.valueOf(now));
                        return 1L;
                    }
                });

        int threads = 10;  // 模拟 10 个实例同时暖场
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return concurrentStore.tryNudge(tenantId, sessionId);
            }));
        }
        ready.await();
        start.countDown();
        long allowed = 0;
        for (Future<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.get())) allowed++;
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(2);  // maxCount 默认 2（间隔 0 隔离后计数上限即唯一约束）
        assertThat(fakeRedis.get("session:nudge:" + tenantId + ":" + sessionId)).isEqualTo("2");
    }
}
