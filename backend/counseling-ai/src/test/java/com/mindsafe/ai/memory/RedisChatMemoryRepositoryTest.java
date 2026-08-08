package com.mindsafe.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisChatMemoryRepository 单元测试（B3 收编：租户段 key + schemaVersion 序列化契约）。
 * <p>
 * 覆盖：租户段 key（有/无租户上下文）、golden JSON 序列化契约（schemaVersion/role/content）、
 * v1 旧格式兼容反序列化、删除/刷新 TTL 走租户段 key。
 */
@ExtendWith(MockitoExtension.class)
class RedisChatMemoryRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisChatMemoryRepository repository;

    private final UUID tenantId = UUID.randomUUID();
    private final String conversationId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        repository = new RedisChatMemoryRepository(redisTemplate, objectMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("B3: 有租户上下文 → key 为 chat:memory:{tenantId}:{conversationId}")
    void saveAll_usesTenantSegmentedKey() {
        TenantContextHolder.set(tenantId);

        repository.saveAll(conversationId, List.of(new UserMessage("你好")));

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPushAll(keyCaptor.capture(), anyList());
        assertThat(keyCaptor.getValue()).isEqualTo("chat:memory:" + tenantId + ":" + conversationId);
        verify(redisTemplate).delete("chat:memory:" + tenantId + ":" + conversationId);
    }

    @Test
    @DisplayName("B3: 无租户上下文（系统作用域/前置认证）→ 回退 system 段")
    void saveAll_fallsBackToSystemSegment() {
        repository.saveAll(conversationId, List.of(new UserMessage("你好")));

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPushAll(keyCaptor.capture(), anyList());
        assertThat(keyCaptor.getValue()).isEqualTo("chat:memory:system:" + conversationId);
    }

    @Test
    @DisplayName("B3: 序列化契约 golden——JSON 含 schemaVersion=1/role/content，TTL 2h")
    void saveAll_serializesWithSchemaVersion() {
        TenantContextHolder.set(tenantId);

        repository.saveAll(conversationId, List.of(new UserMessage("今天有点难过")));

        org.mockito.ArgumentCaptor<List<String>> jsonCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(listOperations).rightPushAll(anyString(), jsonCaptor.capture());
        String json = jsonCaptor.getValue().get(0);
        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"role\":\"USER\"")
                .contains("今天有点难过");
        verify(redisTemplate).expire("chat:memory:" + tenantId + ":" + conversationId, Duration.ofHours(2));
    }

    @Test
    @DisplayName("B3: findByConversationId 反序列化——v1 格式（含 schemaVersion）与旧格式（无字段）均可读")
    void findByConversationId_roundTripsNewAndLegacyJson() {
        TenantContextHolder.set(tenantId);
        String newJson = "{\"schemaVersion\":1,\"role\":\"ASSISTANT\",\"content\":\"老师陪你聊聊\"}";
        String legacyJson = "{\"role\":\"USER\",\"content\":\"旧格式消息\"}";
        when(listOperations.range("chat:memory:" + tenantId + ":" + conversationId, 0, -1))
                .thenReturn(List.of(newJson, legacyJson));

        List<Message> messages = repository.findByConversationId(conversationId);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("老师陪你聊聊");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("旧格式消息");
    }

    @Test
    @DisplayName("B3: deleteByConversationId / refreshTtl 均使用租户段 key")
    void deleteAndRefreshTtl_useTenantSegmentedKey() {
        TenantContextHolder.set(tenantId);
        String expected = "chat:memory:" + tenantId + ":" + conversationId;

        repository.deleteByConversationId(conversationId);
        verify(redisTemplate).delete(expected);

        repository.refreshTtl(conversationId);
        verify(redisTemplate).expire(expected, Duration.ofHours(2));
    }

    @Test
    @DisplayName("B3: findConversationIds 用 SCAN 游标扫当前租户段前缀，提取会话 ID")
    void findConversationIds_scansTenantPrefixOnly() {
        TenantContextHolder.set(tenantId);
        Cursor<String> cursor = org.mockito.Mockito.mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("chat:memory:" + tenantId + ":conv-a", "chat:memory:" + tenantId + ":conv-b");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        List<String> ids = repository.findConversationIds();

        assertThat(ids).containsExactly("conv-a", "conv-b");
        verify(redisTemplate).scan(any(ScanOptions.class));
    }
}
