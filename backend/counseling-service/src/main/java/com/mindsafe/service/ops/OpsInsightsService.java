package com.mindsafe.service.ops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.service.common.CounselingTimeZone;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.NotificationMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.TenantMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 运营洞察（ADMIN-P2-04/05，M10 通知渠道统计 + M12 运营洞察）
 * <p>
 * 通知渠道统计/失败台账、会话质量趋势、预警漏斗、租户健康度。
 * 平台查询 callAsSystem（安全边界由 /api/v1/ops/** 平台角色授权兜底）；R-11 仅聚合无标识。
 * 设计见 doing/83 后台管理端 §5.10/§5.12。
 */
@Service
public class OpsInsightsService {

    private final NotificationMapper notificationMapper;
    private final RiskEventMapper riskEventMapper;
    private final QualityScoreMapper qualityScoreMapper;
    private final ConsentRecordMapper consentRecordMapper;
    private final TenantMapper tenantMapper;
    private final JdbcTemplate jdbcTemplate;

    public OpsInsightsService(NotificationMapper notificationMapper,
                              RiskEventMapper riskEventMapper,
                              QualityScoreMapper qualityScoreMapper,
                              ConsentRecordMapper consentRecordMapper,
                              TenantMapper tenantMapper,
                              JdbcTemplate jdbcTemplate) {
        this.notificationMapper = notificationMapper;
        this.riskEventMapper = riskEventMapper;
        this.qualityScoreMapper = qualityScoreMapper;
        this.consentRecordMapper = consentRecordMapper;
        this.tenantMapper = tenantMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 通知渠道统计（近 30 天，按 channel 计数） */
    public Map<String, Object> channelStats() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<Notification> notifications = TenantContextHolder.callAsSystem(() ->
                notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                        .ge(Notification::getCreatedAt, since)));
        Map<String, Long> byChannel = notifications.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getChannel() == null ? "unknown" : n.getChannel(), Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byChannel", byChannel);
        result.put("total", notifications.size());
        return result;
    }

    /** 通知失败台账（notify_status=dead 的预警，供人工核对/补发）——R-7 脱敏：
     *  仅暴露补发所需最小字段，不含 studentUserId/schoolId 等学生级标识（code-review M2） */
    public List<DeadLedgerEntry> deadLedger(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<RiskEvent> events = TenantContextHolder.callAsSystem(() ->
                riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getNotifyStatus, "dead")
                        .orderByDesc(RiskEvent::getDetectedAt)
                        .last("LIMIT " + safeLimit)));
        return events.stream().map(e -> new DeadLedgerEntry(
                e.getRiskEventId(), e.getTenantId(), e.getRiskLevel(), e.getRiskType(),
                e.getStatus(), e.getDetectedAt(), e.getNotifyStatus())).toList();
    }

    /** 脱敏台账条目（R-7：学生级明细仅 super_admin/audit，ops_admin 仅聚合/最小字段） */
    public record DeadLedgerEntry(
            java.util.UUID riskEventId,
            java.util.UUID tenantId,
            Integer riskLevel,
            String riskType,
            String status,
            Instant detectedAt,
            String notifyStatus) {
    }

    /** 会话质量趋势（近 7 天：日均 overallScore + 样本数） */
    public Map<String, Object> qualityTrend() {
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        List<QualityScore> scores = TenantContextHolder.callAsSystem(() ->
                qualityScoreMapper.selectList(new LambdaQueryWrapper<QualityScore>()
                        .ge(QualityScore::getEvaluatedAt, since)));
        ZoneId zone = CounselingTimeZone.SHANGHAI;
        Map<String, Object> trend = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            Instant dayStart = Instant.now().atZone(zone).toLocalDate().minusDays(i).atStartOfDay(zone).toInstant();
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            List<QualityScore> dayScores = scores.stream()
                    .filter(s -> s.getEvaluatedAt() != null
                            && !s.getEvaluatedAt().isBefore(dayStart) && s.getEvaluatedAt().isBefore(dayEnd))
                    .toList();
            double avg = dayScores.isEmpty() ? 0.0
                    : Math.round(dayScores.stream().map(QualityScore::getOverallScore)
                            .filter(java.util.Objects::nonNull)
                            .mapToDouble(BigDecimal::doubleValue).average().orElse(0.0) * 10.0) / 10.0;
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("avgScore", avg);
            day.put("samples", dayScores.size());
            trend.put(dayStart.atZone(zone).toLocalDate().toString(), day);
        }
        return trend;
    }

    /** 预警漏斗：检出→通知→认领→处置→闭环 各阶段计数 */
    public Map<String, Object> alertFunnel() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<RiskEvent> events = TenantContextHolder.callAsSystem(() ->
                riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                        .ge(RiskEvent::getDetectedAt, since)));
        long total = events.size();
        long notified = events.stream()
                .filter(e -> e.getNotifyStatus() != null && !"pending".equals(e.getNotifyStatus()))
                .count();
        long claimed = events.stream()
                .filter(e -> RiskEvent.STATUS_CLAIMED.equals(e.getStatus())
                        || RiskEvent.STATUS_RESOLVED.equals(e.getStatus())
                        || RiskEvent.STATUS_CLOSED.equals(e.getStatus()))
                .count();
        long resolved = events.stream()
                .filter(e -> RiskEvent.STATUS_RESOLVED.equals(e.getStatus())
                        || RiskEvent.STATUS_CLOSED.equals(e.getStatus()))
                .count();
        long closed = events.stream()
                .filter(e -> RiskEvent.STATUS_CLOSED.equals(e.getStatus()))
                .count();
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("detected", total);
        funnel.put("notified", notified);
        funnel.put("claimed", claimed);
        funnel.put("resolved", resolved);
        funnel.put("closed", closed);
        return funnel;
    }

    /** 租户健康度：按租户聚合未处置数/逾期数（近 30 天） */
    public List<Map<String, Object>> tenantHealth() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<RiskEvent> events = TenantContextHolder.callAsSystem(() ->
                riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                        .ge(RiskEvent::getDetectedAt, since)));
        Map<UUID, List<RiskEvent>> byTenant = events.stream()
                .filter(e -> e.getTenantId() != null)
                .collect(Collectors.groupingBy(RiskEvent::getTenantId));
        // 租户名称映射（BUG-A-005：显示内部 ID → 映射 tenant_name/tenant_code）
        Map<UUID, Tenant> tenants = TenantContextHolder.callAsSystem(() ->
                tenantMapper.selectBatchIds(byTenant.keySet()).stream()
                        .collect(Collectors.toMap(Tenant::getTenantId, t -> t)));
        Instant now = Instant.now();
        List<Map<String, Object>> health = new java.util.ArrayList<>();
        for (Map.Entry<UUID, List<RiskEvent>> entry : byTenant.entrySet()) {
            List<RiskEvent> tenantEvents = entry.getValue();
            long unhandled = tenantEvents.stream()
                    .filter(e -> RiskEvent.STATUS_OPEN.equals(e.getStatus()) || RiskEvent.STATUS_CLAIMED.equals(e.getStatus()))
                    .count();
            long overdue = tenantEvents.stream()
                    .filter(e -> RiskEvent.STATUS_OPEN.equals(e.getStatus()) || RiskEvent.STATUS_CLAIMED.equals(e.getStatus()))
                    .filter(e -> e.getDetectedAt() != null && DurationBetween(e.getDetectedAt(), now) > 60)
                    .count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", entry.getKey());
            Tenant tenant = tenants.get(entry.getKey());
            row.put("tenantName", tenant != null ? tenant.getTenantName() : null);
            row.put("tenantCode", tenant != null ? tenant.getTenantCode() : null);
            row.put("total", tenantEvents.size());
            row.put("unhandled", unhandled);
            row.put("overdue", overdue);
            row.put("health", overdue > 0 ? "red" : unhandled > 0 ? "yellow" : "green");
            health.add(row);
        }
        health.sort((a, b) -> Long.compare((Long) b.get("overdue"), (Long) a.get("overdue")));
        return health;
    }

    private long DurationBetween(Instant start, Instant end) {
        return java.time.Duration.between(start, end).toMinutes();
    }

    // ===== M4 用量报表（ADMIN-P3-02，计量非计费） =====

    /** 用量汇总（近 N 天：按 metric 聚合 value；另附最近活跃学生数） */
    public Map<String, Object> usageSummary(int days) {
        int safeDays = Math.min(Math.max(days, 1), 90);
        Instant since = Instant.now().minus(safeDays, ChronoUnit.DAYS);
        List<Map<String, Object>> rows = TenantContextHolder.callAsSystem(() ->
                jdbcTemplate.queryForList("""
                        SELECT metric, COALESCE(SUM(value), 0) AS total, unit
                        FROM tenant_template.usage_events
                        WHERE event_time >= ?
                        GROUP BY metric, unit
                        ORDER BY metric
                        """, Timestamp.from(since)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", safeDays);
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("metric")), row.get("total"));
        }
        return result;
    }

    // ===== M11 合规视图（ADMIN-P3-03） =====

    /** 告知同意覆盖统计：总数/最近 7 天新增/类型分布 */
    public Map<String, Object> consentStats() {
        List<com.mindsafe.domain.entity.ConsentRecord> all = TenantContextHolder.callAsSystem(() ->
                consentRecordMapper.selectList(null));
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("last7d", all.stream()
                .filter(c -> c.getConsentedAt() != null && !c.getConsentedAt().isBefore(weekAgo))
                .count());
        Map<String, Long> byType = all.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getConsentType() == null ? "unknown" : c.getConsentType(), Collectors.counting()));
        result.put("byType", byType);
        return result;
    }
}
