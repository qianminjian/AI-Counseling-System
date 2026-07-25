package com.mindsafe.ai.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话状态管理器（Redis 持久化）
 * <p>
 * 替代 ConversationServiceImpl 中的 ConcurrentHashMap<UUID, SessionState> 内存方案。
 * Redis key: session:{sessionId}:state，TTL = 2h（与 ChatMemory 对齐）。
 * <p>
 * 对齐计划 Phase 1.6：ConversationState + Redis 状态管理。
 */
@Component
public class ConversationStateManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationStateManager.class);

    private static final String KEY_PREFIX = "session:";
    private static final String KEY_SUFFIX = ":state";
    private static final Duration STATE_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationStateManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建并保存新会话状态
     */
    public ConversationState createState(UUID sessionId, UUID tenantId, UUID studentUserId,
                                         String emotionTag, String channel, int gradeLevel) {
        ConversationState state = ConversationState.create(
                sessionId, tenantId, studentUserId, emotionTag, channel, gradeLevel);
        save(state);
        log.debug("会话状态已创建: sessionId={}", sessionId);
        return state;
    }

    /**
     * 获取会话状态
     */
    public Optional<ConversationState> getState(UUID sessionId) {
        String key = buildKey(sessionId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            ConversationState state = objectMapper.readValue(json, ConversationState.class);
            return Optional.of(state);
        } catch (JsonProcessingException e) {
            log.error("反序列化会话状态失败: sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * 获取会话状态（不存在则抛异常）
     */
    public ConversationState getStateOrThrow(UUID sessionId) {
        return getState(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话状态不存在: " + sessionId));
    }

    /**
     * 保存/更新会话状态
     */
    public void save(ConversationState state) {
        String key = buildKey(state.getSessionId());
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, STATE_TTL);
        } catch (JsonProcessingException e) {
            log.error("序列化会话状态失败: sessionId={}", state.getSessionId(), e);
        }
    }

    /**
     * 删除会话状态（会话结束时调用）
     */
    public void delete(UUID sessionId) {
        String key = buildKey(sessionId);
        redisTemplate.delete(key);
        log.debug("会话状态已删除: sessionId={}", sessionId);
    }

    /**
     * 刷新 TTL（每次交互后延长过期时间）
     */
    public void refreshTtl(UUID sessionId) {
        String key = buildKey(sessionId);
        redisTemplate.expire(key, STATE_TTL);
    }

    /**
     * 检查会话状态是否存在
     */
    public boolean exists(UUID sessionId) {
        String key = buildKey(sessionId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private String buildKey(UUID sessionId) {
        return KEY_PREFIX + sessionId + KEY_SUFFIX;
    }
}

