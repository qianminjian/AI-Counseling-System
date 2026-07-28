package com.mindsafe.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 业务缓存服务（PERF-004：Redis 缓存策略）
 * <p>
 * 缓存策略：
 * - 学生画像：TTL 10min（会话期间高频读取，画像更新时主动失效）
 * - Prompt 配置：TTL 5min（与 PromptVersionService 本地缓存互补）
 * - 会话状态：TTL 30min（活跃会话元数据）
 * - 租户配置：TTL 1h（低频变更）
 * <p>
 * 模式：Cache-Aside（读：缓存→DB→写缓存；写：更新DB→删缓存）
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private static final String PROFILE_PREFIX = "cache:profile:";
    private static final String SESSION_PREFIX = "cache:session:";
    private static final String TENANT_PREFIX = "cache:tenant:";
    private static final String CONFIG_PREFIX = "cache:config:";

    private static final Duration PROFILE_TTL = Duration.ofMinutes(10);
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration TENANT_TTL = Duration.ofHours(1);
    private static final Duration CONFIG_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ===== 学生画像缓存 =====

    /**
     * 获取学生画像（Cache-Aside）
     */
    public <T> Optional<T> getProfile(UUID studentUserId, Class<T> type, Supplier<T> dbLoader) {
        String key = PROFILE_PREFIX + studentUserId;
        return getOrLoad(key, type, dbLoader, PROFILE_TTL);
    }

    /**
     * 画像更新时主动失效
     */
    public void evictProfile(UUID studentUserId) {
        evict(PROFILE_PREFIX + studentUserId);
    }

    // ===== 会话状态缓存 =====

    public <T> Optional<T> getSessionState(UUID sessionId, Class<T> type, Supplier<T> dbLoader) {
        String key = SESSION_PREFIX + sessionId;
        return getOrLoad(key, type, dbLoader, SESSION_TTL);
    }

    public void evictSession(UUID sessionId) {
        evict(SESSION_PREFIX + sessionId);
    }

    // ===== 租户配置缓存 =====

    public <T> Optional<T> getTenantConfig(UUID tenantId, Class<T> type, Supplier<T> dbLoader) {
        String key = TENANT_PREFIX + tenantId;
        return getOrLoad(key, type, dbLoader, TENANT_TTL);
    }

    public void evictTenant(UUID tenantId) {
        evict(TENANT_PREFIX + tenantId);
    }

    // ===== 通用配置缓存 =====

    public <T> Optional<T> getConfig(String configKey, Class<T> type, Supplier<T> dbLoader) {
        String key = CONFIG_PREFIX + configKey;
        return getOrLoad(key, type, dbLoader, CONFIG_TTL);
    }

    public void evictConfig(String configKey) {
        evict(CONFIG_PREFIX + configKey);
    }

    // ===== 通用 Cache-Aside 实现 =====

    private <T> Optional<T> getOrLoad(String key, Class<T> type, Supplier<T> dbLoader, Duration ttl) {
        try {
            // 1. 尝试读缓存
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                T value = objectMapper.readValue(cached, type);
                return Optional.ofNullable(value);
            }
        } catch (Exception e) {
            log.debug("缓存读取失败，降级到 DB: key={}", key, e);
        }

        // 2. 缓存未命中，从 DB 加载
        T value = dbLoader.get();
        if (value == null) {
            return Optional.empty();
        }

        // 3. 写入缓存
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException e) {
            log.warn("缓存序列化失败: key={}", key, e);
        } catch (Exception e) {
            log.debug("缓存写入失败（不影响业务）: key={}", key, e);
        }

        return Optional.of(value);
    }

    private void evict(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("缓存已失效: {}", key);
        } catch (Exception e) {
            log.warn("缓存失效操作失败: key={}", key, e);
        }
    }

    /**
     * 批量失效（租户级清理）
     */
    public void evictByTenant(UUID tenantId) {
        try {
            var keys = redisTemplate.keys("cache:*:" + tenantId + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("租户缓存批量失效: tenantId={}, count={}", tenantId, keys.size());
            }
        } catch (Exception e) {
            log.warn("租户缓存批量失效失败: tenantId={}", tenantId, e);
        }
    }
}
