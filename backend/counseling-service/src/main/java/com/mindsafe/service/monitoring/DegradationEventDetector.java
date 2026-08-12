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
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.UUID;

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
 * <p>
 * <b>多实例语义（专题 F P0-4，audit-report-05）：</b>防抖状态（last_value）存 Redis 键
 * {@code mindsafe:degradation:state:{point}}（TTL=防抖窗口，默认 24h，可配置），
 * 多实例共享同一状态——实例 A 标记 degraded 后实例 B 在窗口内读到同态直接跳过；
 * 同时事件写入走 {@link DegradationEventMapper#insertOnConflictDoNothing}（V48 dedup_key
 * 唯一索引 + ON CONFLICT DO NOTHING）做 DB 幂等兜底（防抖窗口=dedup 时间桶），
 * 双保险消除告警风暴/重复事件污染数据面。
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

    /** 降级防抖状态键前缀（值 "1"=降级中 "0"=正常；TTL=防抖窗口，多实例共享，专题 F P0-4） */
    static final String STATE_KEY_PREFIX = "mindsafe:degradation:state:";

    /** 状态值：降级中 */
    private static final String STATE_DEGRADED = "1";

    /** 状态值：正常 */
    private static final String STATE_NORMAL = "0";

    /** 降级点：TTS（tts_degraded_events_total） */
    static final String POINT_TTS = "tts";

    /** 降级点：LLM（mindsafe_llm_model_fallback_total） */
    static final String POINT_LLM = "llm";

    /** 检测表达式（rate 2m > 0 即处于降级/切换中） */
    private static final String EXPR_TTS_DEGRADED = "sum(rate(tts_degraded_events_total[2m])) > 0";
    private static final String EXPR_LLM_FALLBACK = "sum(rate(mindsafe_llm_model_fallback_total[2m])) > 0";

    private final RestTemplate restTemplate = buildRestTemplate();
    private final StringRedisTemplate redisTemplate;
    private final DegradationEventMapper degradationEventMapper;
    private final String prometheusUrl;

    /**
     * 防抖窗口（默认 24h）：状态键 TTL 与 dedup_key 时间桶同源。
     * 窗口内同态不重复写事件；窗口翻页后（长降级周期）可再次留痕，符合时间线语义。
     */
    private final Duration debounceWindow;

    public DegradationEventDetector(@Value("${mindsafe.monitoring.prometheus-url:http://prometheus:9090}") String prometheusUrl,
                                    StringRedisTemplate redisTemplate,
                                    DegradationEventMapper degradationEventMapper,
                                    @Value("${mindsafe.monitoring.degradation-detector.debounce-window:24h}") Duration debounceWindow) {
        this.prometheusUrl = prometheusUrl;
        this.redisTemplate = redisTemplate;
        this.degradationEventMapper = degradationEventMapper;
        this.debounceWindow = debounceWindow;
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

        // 防抖状态存 Redis（多实例共享，专题 F P0-4）：实例 A 标记 degraded 后，
        // 实例 B 在窗口内读到同态直接跳过（原实例内存状态机无法跨实例共享）
        String stateKey = STATE_KEY_PREFIX + point;
        Boolean wasDegraded = readState(stateKey);

        if (degradedNow && !Boolean.TRUE.equals(wasDegraded)) {
            // 降级发生：写入 auto 事件（last_value 防抖：连续轮询不重复写；DB dedup_key 幂等兜底）
            insertEvent(point, primaryState, fallbackState, DegradationEvent.TRIGGER_AUTO, detail);
            writeState(stateKey, STATE_DEGRADED);
            log.warn("检测到服务降级: point={} {} -> {}", point, primaryState, fallbackState);
        } else if (!degradedNow && Boolean.TRUE.equals(wasDegraded)) {
            // 降级恢复：写入恢复事件（to_state=恢复档位）
            insertEvent(point, fallbackState, primaryState, DegradationEvent.TRIGGER_AUTO, "降级恢复");
            writeState(stateKey, STATE_NORMAL);
            log.info("服务降级恢复: point={} {} -> {}", point, fallbackState, primaryState);
        }
        // 同态（持续降级中/持续正常）不写事件
    }

    /**
     * 读取防抖状态（"1"=降级中 / "0"=正常；键不存在=未知）。
     * Redis 异常按"未知"处理（可能重复写事件，但 DB dedup_key 幂等兜底）。
     */
    private Boolean readState(String stateKey) {
        try {
            String raw = redisTemplate.opsForValue().get(stateKey);
            return raw == null ? null : STATE_DEGRADED.equals(raw);
        } catch (Exception e) {
            log.warn("降级防抖状态读取失败（按未知处理，DB 幂等兜底）: key={}, error={}", stateKey, e.getMessage());
            return null;
        }
    }

    /** 写入防抖状态（TTL=防抖窗口，到期状态复位重新评估）。写失败仅 WARN（DB 幂等兜底）。 */
    private void writeState(String stateKey, String value) {
        try {
            redisTemplate.opsForValue().set(stateKey, value, debounceWindow);
        } catch (Exception e) {
            log.warn("降级防抖状态写入失败（DB 幂等兜底）: key={}, error={}", stateKey, e.getMessage());
        }
    }

    private void insertEvent(String point, String from, String to, String triggerType, String detail) {
        try {
            DegradationEvent event = new DegradationEvent();
            event.setEventId(UUID.randomUUID());
            event.setPoint(point);
            event.setFromState(from);
            event.setToState(to);
            event.setTriggerType(triggerType);
            event.setDetail(detail);
            event.setOccurredAt(Instant.now());
            // DB 幂等（V48）：dedup_key = trigger:point:from->to:时间桶（桶=occurred_at/防抖窗口）。
            // 同窗口重复写入 → 唯一索引冲突 → ON CONFLICT DO NOTHING 跳过（多实例兜底去重）
            event.setDedupKey(buildDedupKey(triggerType, point, from, to, event.getOccurredAt(), debounceWindow.toSeconds()));
            int inserted = degradationEventMapper.insertOnConflictDoNothing(event);
            if (inserted == 0) {
                log.debug("降级事件被幂等去重（同防抖窗口已存在）: dedupKey={}", event.getDedupKey());
            }
        } catch (Exception e) {
            // 附加通道原则（2026-08-10）：降级事件落库失败仅记 WARN，不中断轮询（台账缺失可事后补查指标）
            log.warn("降级事件落库失败: point={} {}->{}, error={}", point, from, to, e.getMessage());
        }
    }

    /**
     * 幂等去重键：{trigger}:{point}:{from}->{to}:{epoch 时间桶}。
     * 时间桶 = epoch 秒 / 防抖窗口秒数——同窗口内同一状态转换唯一（多实例重复写被吞），
     * 窗口翻页后同一转换可再次落库（长降级/恢复周期可多次留痕）。
     */
    static String buildDedupKey(String triggerType, String point, String from, String to, Instant occurredAt, long windowSeconds) {
        long bucket = occurredAt.getEpochSecond() / Math.max(1, windowSeconds);
        return String.format("%s:%s:%s->%s:%d", triggerType, point, from, to, bucket);
    }

    /** 查询 Prometheus 即时向量：result 非空即存在匹配样本（AUDIT-DEEP-008：UriComponentsBuilder 统一编码，对齐 MetricsQueryService M1 修复） */
    private boolean queryHasValue(String expr) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = restTemplate.getForObject(
                UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query")
                        .queryParam("query", expr).build().encode().toUri(), Map.class);
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
