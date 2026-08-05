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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisSessionStateStore 单元测试（P0-1 审计修复：会话状态 Redis 持久化）
 * <p>
 * 覆盖：save（TTL 2h + JSON 序列化 + 序列化/写入失败降级）、
 * get（不存在返回 null + 正常反序列化 + 读取失败降级）、
 * remove / exists 及各自的异常降级路径。
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

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisSessionStateStore(redisTemplate, objectMapper);
    }

    private SessionState newState() {
        return new SessionState(sessionId, UUID.randomUUID(), UUID.randomUUID(),
                "sad", "voice", "M", 0.7, 5);
    }

    @Test
    @DisplayName("save：写入 session:state:{id}，JSON 可反序列化，TTL 2 小时")
    void saveWritesJsonWithTtl() {
        SessionState state = newState();
        state.setTurnCount(6);

        store.save(sessionId, state);

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), jsonCaptor.capture(), eq(Duration.ofHours(2)));
        assertThat(keyCaptor.getValue()).isEqualTo("session:state:" + sessionId);
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
        RedisSessionStateStore failingStore = new RedisSessionStateStore(redisTemplate, failingMapper);

        assertThatCode(() -> failingStore.save(sessionId, newState())).doesNotThrowAnyException();
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("save：Redis 写入异常 → 记日志不抛出")
    void saveRedisFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> store.save(sessionId, newState())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("get：key 不存在返回 null")
    void getMissingReturnsNull() {
        when(valueOperations.get("session:state:" + sessionId)).thenReturn(null);

        assertThat(store.get(sessionId)).isNull();
    }

    @Test
    @DisplayName("get：正常反序列化 SessionState")
    void getRoundTrip() throws Exception {
        SessionState state = newState();
        state.setSessionSummary("学生聊了考试压力");
        state.setLastSummaryTurn(4);
        String json = objectMapper.writeValueAsString(state);
        when(valueOperations.get("session:state:" + sessionId)).thenReturn(json);

        SessionState restored = store.get(sessionId);

        assertThat(restored).isNotNull();
        assertThat(restored.getSessionId()).isEqualTo(sessionId);
        assertThat(restored.getSessionSummary()).isEqualTo("学生聊了考试压力");
        assertThat(restored.getLastSummaryTurn()).isEqualTo(4);
    }

    @Test
    @DisplayName("get：非法 JSON → 返回 null 降级")
    void getInvalidJsonReturnsNull() {
        when(valueOperations.get("session:state:" + sessionId)).thenReturn("{not-valid-json");

        assertThat(store.get(sessionId)).isNull();
    }

    @Test
    @DisplayName("get：Redis 读取异常 → 返回 null 降级")
    void getRedisFailureReturnsNull() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(store.get(sessionId)).isNull();
    }

    @Test
    @DisplayName("remove：删除对应 key")
    void removeDeletesKey() {
        when(redisTemplate.delete("session:state:" + sessionId)).thenReturn(true);

        store.remove(sessionId);

        verify(redisTemplate).delete("session:state:" + sessionId);
    }

    @Test
    @DisplayName("remove：Redis 删除异常 → 记日志不抛出")
    void removeFailureSwallowed() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> store.remove(sessionId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("exists：key 存在返回 true，不存在/null 返回 false")
    void existsVariants() {
        when(redisTemplate.hasKey("session:state:" + sessionId)).thenReturn(true);
        assertThat(store.exists(sessionId)).isTrue();

        when(redisTemplate.hasKey("session:state:" + sessionId)).thenReturn(null);
        assertThat(store.exists(sessionId)).isFalse();
    }

    @Test
    @DisplayName("exists：Redis 异常 → 返回 false 降级")
    void existsFailureReturnsFalse() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(store.exists(sessionId)).isFalse();
    }
}
