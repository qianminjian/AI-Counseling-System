package com.mindsafe.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.DegradationEvent;
import com.mindsafe.domain.mapper.DegradationEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 降级矩阵与手动切换（ADMIN-P2-01/02，M3 服务切换降级监控）
 * <p>
 * 展示：降级点清单 + 当前档位（/health 实时 + Redis 覆盖键 + degradation_events 最近事件）；
 * 操作：手动切换 = 写 Redis 运行时覆盖键 + degradation_events manual 事件 + 抑制 auto 事件
 * （与降级监控文档 §3.5 约定一致：检测器跳过已覆盖点）。
 * <p>
 * <b>记录型切换声明（code-review H1）</b>：当前 Redis 覆盖键仅被矩阵展示与检测器跳过逻辑消费，
 * tts/voice 运行时组件尚未接入覆盖键——切换 = 意图登记 + 事件留痕 + auto 抑制，不改变真实运行档位；
 * 运行时档位联动（tts/voice 读覆盖键选引擎）列入后续批次。UI 与文档已同步标注。
 */
@Service
public class DegradationMatrixService {

    private static final Logger log = LoggerFactory.getLogger(DegradationMatrixService.class);

    /** doing/87 RUNTIME-003：覆盖键 TTL（天）——到期自动回落，防遗忘覆盖长期生效（AC-8） */
    public static final long OVERRIDE_TTL_DAYS = 7L;

    /** 覆盖键前缀（与 DegradationEventDetector.OVERRIDE_KEY_PREFIX 同约定） */
    public static final String OVERRIDE_KEY_PREFIX = "mindsafe:degradation:override:";

    /** 降级点清单（M3 §5.3 盘点） */
    public static final List<String> POINTS = List.of(
            "llm", "tts", "asr", "ser", "voice-policy", "wake-word");

    /** 降级点 → 档位说明（主/备；词表与 /health 探测映射对齐，H2） */
    private static final Map<String, String[]> POINT_STATES = Map.of(
            "llm", new String[]{"primary", "backup"},
            "tts", new String[]{"cosyvoice", "edge_tts"},
            "asr", new String[]{"funasr", "dashscope"},
            "ser", new String[]{"enabled", "disabled"},
            "voice-policy", new String[]{"S0", "S1", "S2"},
            "wake-word", new String[]{"local"});

    private final StringRedisTemplate redisTemplate;
    private final DegradationEventMapper degradationEventMapper;
    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${mindsafe.monitoring.service-probes.tts:http://tts-service:10096}")
    private String ttsUrl;

    @Value("${mindsafe.monitoring.service-probes.voice:http://voice-service:10095}")
    private String voiceUrl;

    public DegradationMatrixService(StringRedisTemplate redisTemplate,
                                    DegradationEventMapper degradationEventMapper) {
        this.redisTemplate = redisTemplate;
        this.degradationEventMapper = degradationEventMapper;
    }

    /** 降级矩阵：每点当前档位 + 覆盖状态 + 最近事件 */
    public List<Map<String, Object>> matrix() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String point : POINTS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("point", point);
            String override = redisTemplate.opsForValue().get(OVERRIDE_KEY_PREFIX + point);
            row.put("overridden", override != null);
            row.put("overrideTo", override);
            row.put("currentState", currentState(point));
            row.put("availableStates", POINT_STATES.getOrDefault(point, new String[0]));
            DegradationEvent latest = latestEvent(point);
            if (latest != null) {
                row.put("latestEvent", Map.of(
                        "from", latest.getFromState(),
                        "to", latest.getToState(),
                        "triggerType", latest.getTriggerType(),
                        "occurredAt", latest.getOccurredAt() != null ? latest.getOccurredAt().toString() : ""));
            }
            result.add(row);
        }
        return result;
    }

    /**
     * 手动切换（AUDIT-DEEP-005 口径统一，2026-08-11）：写 Redis 覆盖键 = **记录型意图登记**
     * （矩阵展示 + 检测器 auto 抑制消费；tts/voice 运行时读取见 doing/87 运行时档位联动，
     * 当前不改变真实运行档位）+ degradation_events manual 事件。
     * 仅 ops_admin/super_admin（SecurityConfig 端点级强制）。
     */
    public void override(String point, String to, String operator, String reason) {
        validatePoint(point);
        String[] states = POINT_STATES.get(point);
        // 精确匹配（code-review M1：前缀匹配会接受残缺档位串）
        if (!java.util.Arrays.asList(states).contains(to)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "无效切换目标 [" + to + "]，可选: " + String.join(" / ", states));
        }
        String key = OVERRIDE_KEY_PREFIX + point;
        String previous = redisTemplate.opsForValue().get(key);
        // doing/87 RUNTIME-003（AC-8）：覆盖键 TTL 7 天——到期自动回落配置默认（Redis TTL 机制）
        redisTemplate.opsForValue().set(key, to, OVERRIDE_TTL_DAYS, java.util.concurrent.TimeUnit.DAYS);
        recordManualEvent(point, previous == null ? "auto" : previous, to, operator, reason);
        log.warn("手动降级切换: point={} -> {}（operator={}, reason={}）", point, to, operator, reason);
    }

    /** 取消覆盖：删 Redis 键回配置默认 + manual 事件 */
    public void cancelOverride(String point, String operator, String reason) {
        validatePoint(point);
        String key = OVERRIDE_KEY_PREFIX + point;
        String current = redisTemplate.opsForValue().get(key);
        if (current == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该降级点当前无覆盖: " + point);
        }
        redisTemplate.delete(key);
        recordManualEvent(point, current, "default", operator, reason);
        log.info("取消手动降级覆盖: point={}（operator={}）", point, operator);
    }

    /** 降级事件时间线（P2-02，消费 degradation_events，倒序） */
    public List<DegradationEvent> events(String point, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return TenantContextHolder.callAsSystem(() ->
                degradationEventMapper.selectList(new LambdaQueryWrapper<DegradationEvent>()
                        .eq(point != null && !point.isBlank(), DegradationEvent::getPoint, point)
                        .orderByDesc(DegradationEvent::getOccurredAt)
                        .last("LIMIT " + safeLimit)));
    }

    private void validatePoint(String point) {
        if (!POINTS.contains(point)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知降级点: " + point);
        }
    }

    private void recordManualEvent(String point, String from, String to, String operator, String reason) {
        DegradationEvent event = new DegradationEvent();
        event.setEventId(UUID.randomUUID());
        event.setPoint(point);
        event.setFromState(from);
        event.setToState(to);
        event.setTriggerType(DegradationEvent.TRIGGER_MANUAL);
        event.setOperator(operator);
        event.setDetail(reason);
        event.setOccurredAt(Instant.now());
        TenantContextHolder.callAsSystem(() -> {
            degradationEventMapper.insert(event);
            return null;
        });
    }

    /** 当前档位：覆盖优先 → /health 实时（按点映射字段，H2）→ 最近事件推断 */
    private String currentState(String point) {
        String override = redisTemplate.opsForValue().get(OVERRIDE_KEY_PREFIX + point);
        if (override != null) {
            return override;
        }
        String probed = probeState(point);
        if (!"unknown".equals(probed)) {
            return probed;
        }
        DegradationEvent latest = latestEvent(point);
        if (latest != null && !"default".equals(latest.getToState()) && !"auto".equals(latest.getToState())) {
            return latest.getToState();
        }
        return POINT_STATES.get(point)[0];
    }

    /** /health 探测（按点映射字段名与词表，code-review H2） */
    private String probeState(String point) {
        try {
            if ("tts".equals(point)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = restTemplate.getForObject(ttsUrl + "/health", Map.class);
                if (body == null) {
                    return "unknown";
                }
                // tts /health engine 词表：cosyvoice-cloud/edge-tts/none → 归一 cosyvoice/edge_tts/异常
                String engine = String.valueOf(body.getOrDefault("engine", ""));
                if ("cosyvoice-cloud".equals(engine) || "cosyvoice".equals(engine)) {
                    return "cosyvoice";
                }
                if ("edge-tts".equals(engine) || "edge_tts".equals(engine)) {
                    return "edge_tts";
                }
                return "异常(" + engine + ")";
            }
            if ("asr".equals(point) || "ser".equals(point)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = restTemplate.getForObject(voiceUrl + "/health", Map.class);
                if (body == null) {
                    return "unknown";
                }
                if ("asr".equals(point)) {
                    return String.valueOf(body.getOrDefault("asr_engine", "unknown"));
                }
                // ser：ser_model != disabled → enabled
                String serModel = String.valueOf(body.getOrDefault("ser_model", ""));
                return "disabled".equals(serModel) ? "disabled" : "enabled";
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private DegradationEvent latestEvent(String point) {
        return TenantContextHolder.callAsSystem(() -> {
            List<DegradationEvent> list = degradationEventMapper.selectList(
                    new LambdaQueryWrapper<DegradationEvent>()
                            .eq(DegradationEvent::getPoint, point)
                            .orderByDesc(DegradationEvent::getOccurredAt)
                            .last("LIMIT 1"));
            return list.isEmpty() ? null : list.get(0);
        });
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(factory);
    }
}
