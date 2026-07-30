package com.mindsafe.service.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 会话状态 Redis 持久化存储（P0-1 审计修复：替代 ConcurrentHashMap 内存缓存）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>Key 格式：{@code session:state:{sessionId}}</li>
 *   <li>Value：Jackson JSON 序列化的 {@link SessionState}</li>
 *   <li>TTL：2 小时（覆盖单次会话最大时长，超时自动清理）</li>
 *   <li>每次 save 刷新 TTL（活跃会话不过期）</li>
 * </ul>
 * <p>
 * 降级策略：Redis 不可用时 log.error 并返回 null（会话中断但不 NPE），
 * 前端收到"会话不存在"后引导重新开启对话。
 */
@Service
public class RedisSessionStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStateStore.class);
    private static final String KEY_PREFIX = "session:state:";
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSessionStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存会话状态（创建或更新），刷新 TTL。
     */
    public void save(UUID sessionId, SessionState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, json, SESSION_TTL);
        } catch (JsonProcessingException e) {
            log.error("会话状态序列化失败: sessionId={}", sessionId, e);
        } catch (Exception e) {
            log.error("Redis 写入失败（会话状态丢失）: sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取会话状态；不存在或 Redis 异常返回 null。
     */
    public SessionState get(UUID sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            if (json == null) return null;
            return objectMapper.readValue(json, SessionState.class);
        } catch (Exception e) {
            log.error("Redis 读取失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 删除会话状态（会话结束时调用）。
     */
    public void remove(UUID sessionId) {
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.error("Redis 删除失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 判断会话是否存在。
     */
    public boolean exists(UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
        } catch (Exception e) {
            log.error("Redis exists 查询失败: sessionId={}", sessionId, e);
            return false;
        }
    }
}
