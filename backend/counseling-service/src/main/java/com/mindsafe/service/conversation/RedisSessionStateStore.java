package com.mindsafe.service.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话状态 Redis 持久化存储（P0-1 审计修复：替代 ConcurrentHashMap 内存缓存）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>Key 格式：{@code session:state:{tenantId}:{sessionId}}（ARCH-010 P2-4：结构防跨租户越权）</li>
 *   <li>Value：Jackson JSON 序列化的 {@link SessionState}</li>
 *   <li>TTL：2 小时（覆盖单次会话最大时长，超时自动清理）</li>
 *   <li>每次 save 刷新 TTL（活跃会话不过期）</li>
 *   <li>兼容迁移：读取先查新格式，未命中回查旧格式（session:state:{sessionId}）并双写迁移，
 *       旧键留 TTL 自然过期清理；remove 双删新旧 key</li>
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

    /** nudge 独立计数器键前缀（T5：与主对象键分离，原子化并发敏感字段） */
    private static final String NUDGE_KEY_PREFIX = "session:nudge:";
    /** 暖场护栏上限（对齐 SessionState.canNudge 快速路径；真值以 Lua 判定为准） */
    private static final int NUDGE_MAX_COUNT = 2;
    /** 暖场最小间隔秒（对齐 SessionState.canNudge） */
    private static final long NUDGE_MIN_INTERVAL_SECONDS = 20;

    /**
     * Lua 原子暖场护栏：count&lt;上限 且 距上次≥间隔 才 INCR+SET 时间戳（T5：消除 get→改→save 非原子丢失更新与并发双发）。
     * ARGV：1=当前 epoch 秒 2=TTL 秒 3=次数上限 4=最小间隔秒；返回 1=放行 0=拦截。
     */
    private static final RedisScript<Long> TRY_NUDGE_SCRIPT = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= tonumber(ARGV[3]) then return 0 end
            local last = redis.call('GET', KEYS[2])
            if last and tonumber(ARGV[1]) - tonumber(last) < tonumber(ARGV[4]) then return 0 end
            redis.call('INCR', KEYS[1])
            redis.call('SET', KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSessionStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 租户隔离 key（ARCH-010 P2-4）：结构上杜绝跨租户 key 碰撞/越权。
     */
    private static String key(UUID tenantId, UUID sessionId) {
        return KEY_PREFIX + tenantId + ":" + sessionId;
    }

    /**
     * 保存会话状态（创建或更新），刷新 TTL。
     */
    public void save(UUID tenantId, UUID sessionId, SessionState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key(tenantId, sessionId), json, SESSION_TTL);
        } catch (JsonProcessingException e) {
            log.error("会话状态序列化失败: sessionId={}", sessionId, e);
        } catch (Exception e) {
            log.error("Redis 写入失败（会话状态丢失）: sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取会话状态；不存在或 Redis 异常返回 null。
     * <p>
     * ARCH-010 P2-4 兼容迁移：先查新格式；未命中回查旧格式（session:state:{sessionId}），
     * 命中后双写新 key（同 TTL），旧键留 TTL 自然过期，无需后台清理任务。
     */
    public SessionState get(UUID tenantId, UUID sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(key(tenantId, sessionId));
            if (json != null) {
                return objectMapper.readValue(json, SessionState.class);
            }
            // 回查旧格式（存量会话，无租户段）
            String legacyJson = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            if (legacyJson == null) {
                return null;
            }
            SessionState state = objectMapper.readValue(legacyJson, SessionState.class);
            // 双写迁移：新 key 落地后后续读取走新格式；迁移失败不影响本次读取（降级警告）
            try {
                redisTemplate.opsForValue().set(key(tenantId, sessionId), legacyJson, SESSION_TTL);
            } catch (Exception e) {
                log.warn("会话状态双写迁移失败（不影响本次读取）: sessionId={}", sessionId, e);
            }
            return state;
        } catch (Exception e) {
            log.error("Redis 读取失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 删除会话状态（会话结束时调用）；新旧 key 双删（旧格式无租户段无法按租户定位）。
     */
    public void remove(UUID tenantId, UUID sessionId) {
        try {
            redisTemplate.delete(key(tenantId, sessionId));
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.error("Redis 删除失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 判断会话是否存在（新格式）。
     */
    public boolean exists(UUID tenantId, UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(tenantId, sessionId)));
        } catch (Exception e) {
            log.error("Redis exists 查询失败: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 原子暖场护栏放行 + 计数（T5 整改）。
     * <p>
     * nudgeCount/lastNudgeAt 真值存独立键（{@code session:nudge:{tenantId}:{sessionId}} 与 {@code :at}），
     * Lua 原子完成「判定 → INCR → 时间戳」；SessionState 中字段降级为读取快照（快速路径判定用）。
     * 返回 true=放行并已计数；Redis 异常保守拦截（返回 false，本次暖场放弃，下个周期重试）。
     */
    public boolean tryNudge(UUID tenantId, UUID sessionId) {
        try {
            Long result = redisTemplate.execute(TRY_NUDGE_SCRIPT,
                    List.of(nudgeCountKey(tenantId, sessionId), nudgeAtKey(tenantId, sessionId)),
                    String.valueOf(Instant.now().getEpochSecond()),
                    String.valueOf(SESSION_TTL.getSeconds()),
                    String.valueOf(NUDGE_MAX_COUNT),
                    String.valueOf(NUDGE_MIN_INTERVAL_SECONDS));
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            log.error("nudge 原子计数失败（保守拦截本次暖场）: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 消息到达时原子清零 nudge 计数（对齐 recordStudentMessage「孩子说话即清零」语义，T5）。
     * <p>
     * 仅删计数键，保留 {@code :at} 时间戳键（与 SessionState.recordStudentMessage 只重置计数一致）。
     */
    public void resetNudgeCounter(UUID tenantId, UUID sessionId) {
        try {
            redisTemplate.delete(nudgeCountKey(tenantId, sessionId));
        } catch (Exception e) {
            log.error("nudge 计数清零失败: sessionId={}", sessionId, e);
        }
    }

    private static String nudgeCountKey(UUID tenantId, UUID sessionId) {
        return NUDGE_KEY_PREFIX + tenantId + ":" + sessionId;
    }

    private static String nudgeAtKey(UUID tenantId, UUID sessionId) {
        return NUDGE_KEY_PREFIX + tenantId + ":" + sessionId + ":at";
    }
}
