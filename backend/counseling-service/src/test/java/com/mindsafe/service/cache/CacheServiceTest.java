package com.mindsafe.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new CacheService(redisTemplate, objectMapper);
    }

    @Test
    void 缓存命中直接返回不查DB() {
        UUID studentId = UUID.randomUUID();
        when(valueOperations.get("cache:profile:" + studentId)).thenReturn("{\"name\":\"test\"}");

        AtomicInteger dbCalls = new AtomicInteger(0);
        Optional<TestDto> result = cacheService.getProfile(studentId, TestDto.class, () -> {
            dbCalls.incrementAndGet();
            return new TestDto("db");
        });

        assertTrue(result.isPresent());
        assertEquals("test", result.get().name);
        assertEquals(0, dbCalls.get());
    }

    @Test
    void 缓存未命中查DB并写缓存() {
        UUID studentId = UUID.randomUUID();
        when(valueOperations.get("cache:profile:" + studentId)).thenReturn(null);

        Optional<TestDto> result = cacheService.getProfile(studentId, TestDto.class, () -> new TestDto("fromDb"));

        assertTrue(result.isPresent());
        assertEquals("fromDb", result.get().name);
        verify(valueOperations).set(eq("cache:profile:" + studentId), contains("fromDb"), eq(Duration.ofMinutes(10)));
    }

    @Test
    void DB返回null时不写缓存() {
        UUID studentId = UUID.randomUUID();
        when(valueOperations.get("cache:profile:" + studentId)).thenReturn(null);

        Optional<TestDto> result = cacheService.getProfile(studentId, TestDto.class, () -> null);

        assertTrue(result.isEmpty());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 缓存反序列化失败降级到DB() {
        UUID studentId = UUID.randomUUID();
        when(valueOperations.get("cache:profile:" + studentId)).thenReturn("{invalid json}");

        Optional<TestDto> result = cacheService.getProfile(studentId, TestDto.class, () -> new TestDto("fallback"));

        assertTrue(result.isPresent());
        assertEquals("fallback", result.get().name);
    }

    @Test
    void evictProfile删除缓存() {
        UUID studentId = UUID.randomUUID();
        when(redisTemplate.delete("cache:profile:" + studentId)).thenReturn(true);
        cacheService.evictProfile(studentId);
        verify(redisTemplate).delete("cache:profile:" + studentId);
    }

    @Test
    void evictSession删除会话缓存() {
        UUID sessionId = UUID.randomUUID();
        when(redisTemplate.delete("cache:session:" + sessionId)).thenReturn(true);
        cacheService.evictSession(sessionId);
        verify(redisTemplate).delete("cache:session:" + sessionId);
    }

    @Test
    void evictByTenant批量删除() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.keys("cache:*:" + tenantId + "*")).thenReturn(Set.of("cache:profile:" + tenantId, "cache:session:" + tenantId));
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        cacheService.evictByTenant(tenantId);
        verify(redisTemplate).delete(anyCollection());
    }

    @Test
    void Redis异常不影响业务() {
        UUID studentId = UUID.randomUUID();
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        Optional<TestDto> result = cacheService.getProfile(studentId, TestDto.class, () -> new TestDto("resilient"));

        assertTrue(result.isPresent());
        assertEquals("resilient", result.get().name);
    }

    @Test
    void getConfig使用5分钟TTL() {
        when(valueOperations.get("cache:config:app_settings")).thenReturn(null);

        cacheService.getConfig("app_settings", TestDto.class, () -> new TestDto("cfg"));

        verify(valueOperations).set(eq("cache:config:app_settings"), anyString(), eq(Duration.ofMinutes(5)));
    }

    // 测试用 DTO
    public static class TestDto {
        public String name;
        public TestDto() {}
        public TestDto(String name) { this.name = name; }
    }
}
