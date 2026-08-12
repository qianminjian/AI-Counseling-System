package com.mindsafe.ai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis 实现的 ChatMemoryRepository（替代 InMemoryChatMemoryRepository）
 * <p>
 * 特性：
 * - 会话上下文 TTL = 2h（超时自动清理，避免 Redis 内存泄漏）
 * - 消息序列化为 JSON 存储在 Redis List 中
 * - key 格式：chat:memory:{tenantId}:{conversationId}（B3 收编：对齐 ARCH-010 session:state 租户段惯例，
 *   结构防跨租户 key 碰撞；无租户上下文（系统作用域/前置认证）回退 system 段）
 * - 序列化含 schemaVersion 字段（B3：跨进程序列化契约显式版本化，元数据变更不再静默损坏）
 * <p>
 * 对齐计划 Phase 1.5：ChatMemory Redis 持久化。
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository, ChatMemoryAppender {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String KEY_PREFIX = "chat:memory:";
    /** 无租户上下文（系统作用域）时的 key 段，避免与真实租户数据混写共享空间 */
    private static final String SYSTEM_SEGMENT = "system";
    /** 序列化契约版本（B3）：
     * v1 = {role, content}；新增字段必须升版本并保证旧版本可读 */
    private static final int SCHEMA_VERSION = 1;
    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, DEFAULT_TTL);
    }

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String key = buildKey(conversationId);
        List<String> jsonMessages = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonMessages == null || jsonMessages.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(jsonMessages.size());
        for (String json : jsonMessages) {
            try {
                messages.add(deserializeMessage(json));
            } catch (JsonProcessingException e) {
                log.warn("反序列化消息失败，跳过: conversationId={}, error={}", conversationId, e.getMessage());
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = buildKey(conversationId);

        // 先删除旧数据，再写入新数据（全量覆盖，与 MessageWindowChatMemory 语义一致）
        redisTemplate.delete(key);

        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<String> jsonMessages = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                jsonMessages.add(serializeMessage(message));
            } catch (JsonProcessingException e) {
                log.warn("序列化消息失败，跳过: conversationId={}, error={}", conversationId, e.getMessage());
            }
        }

        if (!jsonMessages.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(key, jsonMessages);
            redisTemplate.expire(key, ttl);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        String key = buildKey(conversationId);
        redisTemplate.delete(key);
        log.debug("删除对话记忆: conversationId={}", conversationId);
    }

    /**
     * 原子追加一条消息（doing/92 R-015，ChatMemoryAppender）：
     * rightPush 单条写 + 刷新 TTL，不做 find+saveAll 整表 read-modify-write——
     * 并发召回/对话写入不互相覆盖；序列化契约与既有消息一致。
     */
    @Override
    public void append(String conversationId, Message message) {
        String key = buildKey(conversationId);
        try {
            redisTemplate.opsForList().rightPush(key, serializeMessage(message));
            redisTemplate.expire(key, ttl);
        } catch (JsonProcessingException e) {
            log.warn("追加记忆消息序列化失败，跳过: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 会话记忆是否已存在（doing/92 R-015 召回守卫）：EXISTS key 原子判空，
     * 避免记忆为空（TTL 过期/从未写入）时追加出悬空更正消息。
     */
    @Override
    public boolean hasMessages(String conversationId) {
        String key = buildKey(conversationId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 会话 ID 扫描（B3 收编）：以当前租户段为前缀 + SCAN 游标非阻塞遍历，
     * 替代原 KEYS 全库阻塞扫描；仅返回当前租户上下文内的会话 ID。
     */
    @Override
    public List<String> findConversationIds() {
        String prefix = KEY_PREFIX + currentTenantSegment() + ":";
        List<String> conversationIds = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                conversationIds.add(cursor.next().substring(prefix.length()));
            }
        }
        return conversationIds;
    }

    /**
     * 刷新 TTL（每次对话交互后调用，延长过期时间）
     */
    public void refreshTtl(String conversationId) {
        String key = buildKey(conversationId);
        redisTemplate.expire(key, ttl);
    }

    // ===== 序列化/反序列化 =====

    /**
     * 序列化为带 schemaVersion 的 JSON（B3：跨进程序列化契约显式版本化；golden 测试锁格式）。
     */
    private String serializeMessage(Message message) throws JsonProcessingException {
        Map<String, Object> data = Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "role", message.getMessageType().name(),
                "content", message.getText()
        );
        return objectMapper.writeValueAsString(data);
    }

    private Message deserializeMessage(String json) throws JsonProcessingException {
        Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
        // 兼容 v1（无 schemaVersion 字段，B3 前的存量数据）与当前版本
        int schemaVersion = data.get("schemaVersion") instanceof Number n ? n.intValue() : 1;
        String role = (String) data.get("role");
        String content = (String) data.get("content");
        if (schemaVersion > SCHEMA_VERSION) {
            log.warn("对话记忆 schemaVersion={} 高于当前支持 {}，按当前版本尽力解析: json={}", schemaVersion, SCHEMA_VERSION, json);
        }

        return switch (role) {
            case "USER" -> new UserMessage(content);
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM" -> new SystemMessage(content);
            default -> new UserMessage(content); // 兜底
        };
    }

    /**
     * 租户隔离 key（B3 收编）：{@code chat:memory:{tenantId}:{conversationId}}，对齐 ARCH-010 session:state 惯例；
     * 无租户上下文（系统作用域/测试）回退 system 段。
     */
    private String buildKey(String conversationId) {
        return KEY_PREFIX + currentTenantSegment() + ":" + conversationId;
    }

    private static String currentTenantSegment() {
        UUID tenantId = TenantContextHolder.get();
        return tenantId != null ? tenantId.toString() : SYSTEM_SEGMENT;
    }
}
