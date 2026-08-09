package com.mindsafe.service.monitoring;

import com.mindsafe.common.tenant.TenantContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.AlertEvent;
import com.mindsafe.domain.mapper.AlertEventMapper;
import com.mindsafe.service.alert.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警事件采集器（OPS-MON-008，doing/83 服务降级监控 §3.6）
 * <p>
 * 管理端 M2 告警中心的历史查询依赖 alert_events（AlertManager 仅保留 120h）：
 * <ul>
 *   <li>60s 拉取 AlertManager /api/v2/alerts → upsert（source=fingerprint 唯一键去重）</li>
 *   <li>firing→resolved 流转：本次列表缺失的 firing 告警标记恢复；ack/closed 由管理端 API 更新</li>
 *   <li>AlertService 业务告警发出时同步落库（source=alertservice，由 WeCom/Logging 实现尾部调用）</li>
 * </ul>
 * 验收：AC-10（doing/83 服务降级监控 §五）。
 */
@Component
public class AlertEventCollector {

    private static final Logger log = LoggerFactory.getLogger(AlertEventCollector.class);

    private final RestTemplate restTemplate = buildRestTemplate();
    private final AlertEventMapper alertEventMapper;
    private final String alertmanagerUrl;

    public AlertEventCollector(@Value("${mindsafe.monitoring.alertmanager-url:http://alertmanager:9093}") String alertmanagerUrl,
                               AlertEventMapper alertEventMapper) {
        this.alertmanagerUrl = alertmanagerUrl;
        this.alertEventMapper = alertEventMapper;
    }

    /**
     * 60s 周期采集（cron 可配置）。拉取 AlertManager active 告警 → upsert → 缺失项流转 resolved。
     * 定时任务线程无租户上下文：runAsSystem 显式声明系统作用域（M1-003 惯例，H1 双保险）。
     */
    @Scheduled(cron = "${mindsafe.monitoring.alert-collector.cron:0 * * * * ?}")
    public void collect() {
        TenantContextHolder.runAsSystem(this::collectInternal);
    }

    private void collectInternal() {
        List<?> alerts;
        try {
            alerts = restTemplate.getForObject(alertmanagerUrl + "/api/v2/alerts", List.class);
        } catch (RestClientException e) {
            log.warn("告警采集拉取 AlertManager 失败: {}", e.getMessage());
            return;
        }
        if (alerts == null) {
            return;
        }

        Set<String> seenFingerprints = new HashSet<>();
        for (Object item : alerts) {
            if (!(item instanceof Map<?, ?> alert)) {
                continue;
            }
            upsertAlert(castMap(alert));
            String fp = String.valueOf(castMap(alert).getOrDefault("fingerprint", ""));
            if (!fp.isBlank()) {
                seenFingerprints.add(fp);
            }
        }
        markMissingAsResolved(seenFingerprints);
    }

    /**
     * 保留 30 天（§6.4 口径，AC-10）：每日清理已恢复超 30 天的告警事件（缺口 3——
     * 既有 DataRetentionCleanupJob 不覆盖本表，防无界增长）。
     */
    @Scheduled(cron = "${mindsafe.monitoring.alert-collector.cleanup-cron:0 45 3 * * ?}")
    public void cleanup() {
        TenantContextHolder.runAsSystem(() -> {
            Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);
            int removed = alertEventMapper.delete(new LambdaQueryWrapper<AlertEvent>()
                    .eq(AlertEvent::getStatus, AlertEvent.STATUS_RESOLVED)
                    .lt(AlertEvent::getResolvedAt, threshold));
            log.info("告警事件清理完成: 删除 {} 条（resolved < {}）", removed, threshold);
        });
    }

    /**
     * 业务告警落库（source=alertservice）。由 WeComAlertService / LoggingAlertService
     * sendAlert 尾部调用（发出即留痕，无论外呼成败）。
     */
    public void record(AlertService.AlertLevel level, String title, String detail) {
        AlertEvent event = new AlertEvent();
        event.setSource(AlertEvent.SOURCE_ALERTSERVICE);
        event.setRuleName(title);
        event.setSeverity(level.name());
        event.setStatus(AlertEvent.STATUS_FIRING);
        event.setSummary(title);
        event.setDetail(detail);
        event.setFiredAt(Instant.now());
        event.setCreatedAt(Instant.now());
        alertEventMapper.insert(event);
        log.debug("业务告警已落库: level={}, title={}", level, title);
    }

    /** upsert：按 source+fingerprint 查库，存在则更新状态/时间，不存在则插入 */
    private void upsertAlert(Map<String, Object> alert) {
        String fingerprint = String.valueOf(alert.getOrDefault("fingerprint", ""));
        String alertname = readLabel(alert, "alertname");
        String severity = readLabel(alert, "severity");
        Map<String, Object> status = castMap(alert.get("status"));
        String state = String.valueOf(status.getOrDefault("state", "active"));
        Map<String, Object> annotations = castMap(alert.get("annotations"));
        String summary = String.valueOf(annotations.getOrDefault("summary", alertname));
        String detail = String.valueOf(annotations.getOrDefault("description", ""));
        Instant startsAt = parseTime(alert.get("startsAt"));
        Instant endsAt = parseTime(alert.get("endsAt"));

        AlertEvent existing = fingerprint.isBlank() ? null
                : alertEventMapper.selectOne(new LambdaQueryWrapper<AlertEvent>()
                        .eq(AlertEvent::getSource, AlertEvent.SOURCE_ALERTMANAGER)
                        .eq(AlertEvent::getFingerprint, fingerprint));

        boolean resolved = "resolved".equals(state) || (endsAt != null && endsAt.isAfter(Instant.EPOCH));
        if (existing != null) {
            // 状态双向流转：firing→resolved（恢复）；resolved→firing（复燃，同 fingerprint 再次触发，M1）
            // ack/closed 由管理端 API 更新，采集器不覆盖
            if (resolved && AlertEvent.STATUS_FIRING.equals(existing.getStatus())) {
                existing.setStatus(AlertEvent.STATUS_RESOLVED);
                existing.setResolvedAt(endsAt != null && endsAt.isAfter(Instant.EPOCH) ? endsAt : Instant.now());
                alertEventMapper.updateById(existing);
            } else if (!resolved && AlertEvent.STATUS_RESOLVED.equals(existing.getStatus())) {
                existing.setStatus(AlertEvent.STATUS_FIRING);
                existing.setFiredAt(startsAt != null ? startsAt : Instant.now());
                existing.setResolvedAt(null);
                alertEventMapper.updateById(existing);
            }
            return;
        }

        AlertEvent event = new AlertEvent();
        event.setSource(AlertEvent.SOURCE_ALERTMANAGER);
        event.setFingerprint(fingerprint);
        event.setRuleName(alertname);
        event.setSeverity(severity);
        event.setStatus(resolved ? AlertEvent.STATUS_RESOLVED : AlertEvent.STATUS_FIRING);
        event.setSummary(summary);
        event.setDetail(detail);
        event.setFiredAt(startsAt != null ? startsAt : Instant.now());
        if (resolved && endsAt != null && endsAt.isAfter(Instant.EPOCH)) {
            event.setResolvedAt(endsAt);
        }
        event.setCreatedAt(Instant.now());
        alertEventMapper.insert(event);
    }

    /**
     * 本次列表缺失的 firing 告警（alertmanager 来源）→ 标记 resolved（AlertManager 已移除=已恢复）。
     * 防抖（M3）：仅当 firedAt 早于 2 个采集周期（2×60s）才允许流转——AlertManager 重启/瞬时空列表
     * 时避免把全部 firing 误标恢复。
     */
    private void markMissingAsResolved(Set<String> seenFingerprints) {
        Instant threshold = Instant.now().minus(Duration.ofSeconds(2 * 60));
        List<AlertEvent> firing = alertEventMapper.selectList(new LambdaQueryWrapper<AlertEvent>()
                .eq(AlertEvent::getSource, AlertEvent.SOURCE_ALERTMANAGER)
                .eq(AlertEvent::getStatus, AlertEvent.STATUS_FIRING));
        for (AlertEvent event : firing) {
            boolean missing = event.getFingerprint() == null || !seenFingerprints.contains(event.getFingerprint());
            boolean oldEnough = event.getFiredAt() != null && event.getFiredAt().isBefore(threshold);
            if (missing && oldEnough) {
                event.setStatus(AlertEvent.STATUS_RESOLVED);
                event.setResolvedAt(Instant.now());
                alertEventMapper.updateById(event);
            }
        }
    }

    private static String readLabel(Map<String, Object> alert, String key) {
        Map<String, Object> labels = castMap(alert.get("labels"));
        return String.valueOf(labels.getOrDefault(key, ""));
    }

    private static Instant parseTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value)).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /** 采集外呼必须带超时：AlertManager 不可达时不能挂死调度线程（WeComAlertService 同模式） */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
