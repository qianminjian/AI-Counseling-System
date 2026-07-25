package com.mindsafe.ai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis 实现的 ChatMemoryRepository（替代 InMemoryChatMemoryRepository）
 * <p>
 * 特性：
 * - 会话上下文 TTL = 2h（超时自动清理，避免 Redis 内存泄漏）
 * - 消息序列化为 JSON 存储在 Redis List 中
 * - key 格式：chat:memory:{conversationId}
 * <p>
 * 对齐计划 Phase 1.5：ChatMemory Redis 持久化。
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String KEY_PREFIX = "chat:memory:";
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
    public List<String> findConversationIds() {
        // 扫描 Redis 中所有 chat:memory:* 的 key，提取 conversationId
        var keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(k -> k.substring(KEY_PREFIX.length()))
                .toList();
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
     * 刷新 TTL（每次对话交互后调用，延长过期时间）
     */
    public void refreshTtl(String conversationId) {
        String key = buildKey(conversationId);
        redisTemplate.expire(key, ttl);
    }

    // ===== 序列化/反序列化 =====

    private String serializeMessage(Message message) throws JsonProcessingException {
        Map<String, Object> data = Map.of(
                "role", message.getMessageType().name(),
                "content", message.getText()
        );
        return objectMapper.writeValueAsString(data);
    }

    private Message deserializeMessage(String json) throws JsonProcessingException {
        Map<String, String> data = objectMapper.readValue(json, new TypeReference<>() {});
        String role = data.get("role");
        String content = data.get("content");

        return switch (role) {
            case "USER" -> new UserMessage(content);
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM" -> new SystemMessage(content);
            default -> new UserMessage(content); // 兜底
        };
    }

    private String buildKey(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
