package com.mindsafe.service.monitoring;

import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.DegradationEvent;
import com.mindsafe.domain.mapper.DegradationEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降级事件检测器（OPS-MON-007，doing/83 服务降级监控 §3.5）
 * <p>
 * 自动降级事件由本检测器落库（manual 事件由管理端切换 API 写库）：
 * <ul>
 *   <li>30s 轮询 Prometheus 指标（tts_degraded_events_total / mindsafe_llm_model_fallback_total）</li>
 *   <li>降级出现 → 写 auto 事件；恢复 → 写恢复事件；连续同态不重复写（last_value 防抖）</li>
 *   <li>管理端手动覆盖键存在时跳过该点（避免与 manual 双写）</li>
 *   <li>Redis SETNX 分布式锁防多实例并发重复扫描</li>
 * </ul>
 * 验收：AC-9（doing/83 服务降级监控 §五）。
 */
@Component
public class DegradationEventDetector {

    private static final Logger log = LoggerFactory.getLogger(DegradationEventDetector.class);

    /** 手动覆盖键前缀（管理端 M3 切换写 Redis 运行时键，检测器读同键跳过） */
    public static final String OVERRIDE_KEY_PREFIX = "mindsafe:degradation:override:";

    /** 多实例扫描互斥锁键 */
    static final String SCAN_LOCK_KEY = "mindsafe:degradation:detector:lock";

    /** 锁持有时间（60s，与 30s 扫描周期留足余量，防锁过期竞态） */
    static final Duration SCAN_LOCK_TTL = Duration.ofSeconds(60);

    /** 降级点：TTS（tts_degraded_events_total） */
    static final String POINT_TTS = "tts";

    /** 降级点：LLM（mindsafe_llm_model_fallback_total） */
    static final String POINT_LLM = "llm";

    /** 检测表达式（rate 2m > 0 即处于降级/切换中） */
    private static final String EXPR_TTS_DEGRADED = "sum(rate(tts_degraded_events_total[2m])) > 0";
    private static final String EXPR_LLM_FALLBACK = "sum(rate(mindsafe_llm_model_fallback_total[2m])) > 0";

    /** 降级点 → 是否处于降级态（内存状态机，last_value 防抖） */
    private final Map<String, Boolean> degradedState = new ConcurrentHashMap<>();

    private final RestTemplate restTemplate = buildRestTemplate();
    private final StringRedisTemplate redisTemplate;
    private final DegradationEventMapper degradationEventMapper;
    private final String prometheusUrl;

    public DegradationEventDetector(@Value("${mindsafe.monitoring.prometheus-url:http://prometheus:9090}") String prometheusUrl,
                                    StringRedisTemplate redisTemplate,
                                    DegradationEventMapper degradationEventMapper) {
        this.prometheusUrl = prometheusUrl;
        this.redisTemplate = redisTemplate;
        this.degradationEventMapper = degradationEventMapper;
    }

    /** 监控查询外呼必须带超时：Prometheus 不可达时不能挂死调度线程（WeComAlertService 同模式） */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }

    /**
     * 30s 周期扫描（cron 可配置）。单次扫描：拿锁 → 逐点判定 → 状态机落库。
     * 定时任务线程无租户上下文：runAsSystem 显式声明系统作用域（M1-003 惯例，
     * SlaEscalationScanner 同模式），配合拦截器 IGNORE_TABLES 双保险（H1）。
     */
    @Scheduled(cron = "${mindsafe.monitoring.degradation-detector.cron:*/30 * * * * ?}")
    public void scan() {
        TenantContextHolder.runAsSystem(this::scanInternal);
    }

    private void scanInternal() {
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(SCAN_LOCK_KEY, token, SCAN_LOCK_TTL))) {
            log.debug("降级检测扫描被分布式锁拦截（其他实例正在扫描）");
            return;
        }
        try {
            scanPoint(POINT_TTS, EXPR_TTS_DEGRADED, "cosyvoice", "edge_tts", "TTS 主引擎降级到兜底引擎");
            scanPoint(POINT_LLM, EXPR_LLM_FALLBACK, "primary", "backup", "LLM 主供应商切换至备用");
        } finally {
            // 所有权校验后释放（防锁过期后误删他人锁，M2）
            if (token.equals(redisTemplate.opsForValue().get(SCAN_LOCK_KEY))) {
                redisTemplate.delete(SCAN_LOCK_KEY);
            }
        }
    }

    private void scanPoint(String point, String expr, String primaryState, String fallbackState, String detail) {
        // 管理端手动覆盖期间：不写 auto 事件（避免与 manual 双写，§3.5）
        if (Boolean.TRUE.equals(redisTemplate.hasKey(OVERRIDE_KEY_PREFIX + point))) {
            log.debug("降级点 [{}] 处于手动覆盖中，跳过自动事件", point);
            return;
        }

        boolean degradedNow;
        try {
            degradedNow = queryHasValue(expr);
        } catch (RestClientException e) {
            log.warn("降级检测查询 Prometheus 失败（point={}）: {}", point, e.getMessage());
            return;
        }

        Boolean wasDegraded = degradedState.get(point);
        if (degradedNow && !Boolean.TRUE.equals(wasDegraded)) {
            // 降级发生：写入 auto 事件（last_value 防抖：连续轮询不重复写）
            insertEvent(point, primaryState, fallbackState, DegradationEvent.TRIGGER_AUTO, detail);
            degradedState.put(point, true);
            log.warn("检测到服务降级: point={} {} -> {}", point, primaryState, fallbackState);
        } else if (!degradedNow && Boolean.TRUE.equals(wasDegraded)) {
            // 降级恢复：写入恢复事件（to_state=恢复档位）
            insertEvent(point, fallbackState, primaryState, DegradationEvent.TRIGGER_AUTO, "降级恢复");
            degradedState.put(point, false);
            log.info("服务降级恢复: point={} {} -> {}", point, fallbackState, primaryState);
        }
        // 同态（持续降级中/持续正常）不写事件
    }

    private void insertEvent(String point, String from, String to, String triggerType, String detail) {
        DegradationEvent event = new DegradationEvent();
        event.setPoint(point);
        event.setFromState(from);
        event.setToState(to);
        event.setTriggerType(triggerType);
        event.setDetail(detail);
        event.setOccurredAt(Instant.now());
        degradationEventMapper.insert(event);
    }

    /** 查询 Prometheus 即时向量：result 非空即存在匹配样本 */
    private boolean queryHasValue(String expr) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = restTemplate.getForObject(
                prometheusUrl + "/api/v1/query?query=" + expr, Map.class);
        if (body == null || !"success".equals(body.get("status"))) {
            return false;
        }
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        if (data == null) {
            return false;
        }
        List<?> result = (List<?>) data.get("result");
        return result != null && !result.isEmpty();
    }
}
