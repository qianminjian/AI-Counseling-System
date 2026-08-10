package com.mindsafe.service.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.SlaEscalationLog;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.SlaEscalationLogMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 业务信号风险全景与时效统计（ADMIN-P1-04，M8 业务信号与预警处置监控，纯查询）
 * <p>
 * 风险全景：红橙黄绿分布（riskLevel 1-4）、今日新增/未处置、近 7 天趋势；
 * 时效监控：按等级聚合 SLA 达标率/逾期率/P95 处理时长（检出→处置）。
 * 平台查询：risk_events 为租户表，callAsSystem 声明系统作用域（安全边界由
 * /api/v1/ops/** 平台角色授权兜底）。设计见 doing/83 后台管理端 §5.8。
 */
@Service
public class RiskOverviewService {

    /** SLA 处置阈值（分钟，权威映射 RiskLevel：RED=3/ORANGE=2/YELLOW=1/GREEN=0；处置口径 §8.3） */
    private static final Map<Integer, Long> SLA_DISPOSE_MINUTES = Map.of(
            3, 15L,   // RED（S0）处置 15min
            2, 60L,   // ORANGE 处置 1h
            1, 480L,  // YELLOW 处置 1 工作日（8h）
            0, 1440L  // GREEN 处置 1 天（超时口径宽松）
    );

    /** 统计窗口：近 30 天 */
    private static final int WINDOW_DAYS = 30;

    private final RiskEventMapper riskEventMapper;
    private final SlaEscalationLogMapper slaEscalationLogMapper;

    public RiskOverviewService(RiskEventMapper riskEventMapper,
                               SlaEscalationLogMapper slaEscalationLogMapper) {
        this.riskEventMapper = riskEventMapper;
        this.slaEscalationLogMapper = slaEscalationLogMapper;
    }

    /** 风险全景：分布/今日新增/未处置/近 7 天趋势 */
    public Map<String, Object> overview(UUID tenantId) {
        List<RiskEvent> events = loadWindow(tenantId, WINDOW_DAYS);
        Instant now = Instant.now();
        Instant todayStart = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();

        Map<String, Long> levelDistribution = events.stream()
                .collect(Collectors.groupingBy(e -> levelName(e.getRiskLevel()), Collectors.counting()));

        long todayNew = events.stream().filter(e -> !e.getDetectedAt().isBefore(todayStart)).count();
        long unhandled = events.stream()
                .filter(e -> RiskEvent.STATUS_OPEN.equals(e.getStatus()) || RiskEvent.STATUS_CLAIMED.equals(e.getStatus()))
                .count();

        // 近 7 天趋势（按 Asia/Shanghai 日分组）
        Map<String, Long> trend = new LinkedHashMap<>();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        for (int i = 6; i >= 0; i--) {
            Instant dayStart = now.atZone(zone).toLocalDate().minusDays(i).atStartOfDay(zone).toInstant();
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            String day = dayStart.atZone(zone).toLocalDate().toString();
            long count = events.stream()
                    .filter(e -> !e.getDetectedAt().isBefore(dayStart) && e.getDetectedAt().isBefore(dayEnd))
                    .count();
            trend.put(day, count);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("levelDistribution", levelDistribution);
        result.put("todayNew", todayNew);
        result.put("unhandled", unhandled);
        result.put("trend7d", trend);
        return result;
    }

    /** 时效监控：按等级聚合（事件数/达标数/达标率/P95 处理时长/逾期数） */
    public List<Map<String, Object>> slaStats(UUID tenantId) {
        List<RiskEvent> events = loadWindow(tenantId, WINDOW_DAYS);
        List<RiskEvent> resolved = events.stream()
                .filter(e -> e.getResolvedAt() != null && e.getDetectedAt() != null)
                .toList();

        Map<Integer, List<RiskEvent>> byLevel = resolved.stream()
                .collect(Collectors.groupingBy(e -> e.getRiskLevel() == null ? 1 : e.getRiskLevel()));

        List<Map<String, Object>> stats = new ArrayList<>();
        for (Map.Entry<Integer, List<RiskEvent>> entry : byLevel.entrySet()) {
            int level = entry.getKey();
            List<RiskEvent> levelEvents = entry.getValue();
            long slaMinutes = SLA_DISPOSE_MINUTES.getOrDefault(level, 1440L);
            long onTime = levelEvents.stream()
                    .filter(e -> Duration.between(e.getDetectedAt(), e.getResolvedAt()).toMinutes() <= slaMinutes)
                    .count();
            long overdue = levelEvents.size() - onTime;
            List<Long> durations = levelEvents.stream()
                    .map(e -> Duration.between(e.getDetectedAt(), e.getResolvedAt()).toMinutes())
                    .sorted()
                    .toList();
            long p95 = durations.isEmpty() ? 0 : durations.get((int) Math.ceil(durations.size() * 0.95) - 1);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("riskLevel", level);
            row.put("levelName", levelName(level));
            row.put("total", levelEvents.size());
            row.put("onTime", onTime);
            row.put("overdue", overdue);
            row.put("onTimeRate", levelEvents.isEmpty() ? 0.0 : Math.round(onTime * 1000.0 / levelEvents.size()) / 10.0);
            row.put("p95Minutes", p95);
            stats.add(row);
        }
        stats.sort(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("riskLevel")).reversed());
        return stats;
    }

    /** 近 30 天事件（租户可空=平台全量；时间窗口防全表扫描） */
    private List<RiskEvent> loadWindow(UUID tenantId, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return TenantContextHolder.callAsSystem(() ->
                riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                        .eq(tenantId != null, RiskEvent::getTenantId, tenantId)
                        .ge(RiskEvent::getDetectedAt, since)));
    }

    // ===== M8 逾期处置（ADMIN-P1-05：清单/转派/强制关闭，X-Confirm + 留痕） =====

    /** 逾期清单（R-7 脱敏：仅暴露处置所需最小字段，不含 studentUserId/schoolId 等
     *  学生级标识——缺口 2，与 dead-ledger 同口径） */
    public List<OverdueEntry> overdueList(UUID tenantId) {
        List<RiskEvent> events = loadWindow(tenantId, WINDOW_DAYS);
        Instant now = Instant.now();
        return events.stream()
                .filter(e -> RiskEvent.STATUS_OPEN.equals(e.getStatus()) || RiskEvent.STATUS_CLAIMED.equals(e.getStatus()))
                .filter(e -> e.getDetectedAt() != null
                        && Duration.between(e.getDetectedAt(), now).toMinutes()
                        > SLA_DISPOSE_MINUTES.getOrDefault(e.getRiskLevel() == null ? 0 : e.getRiskLevel(), 1440L))
                .sorted(Comparator.comparing(RiskEvent::getDetectedAt))
                .map(e -> new OverdueEntry(
                        e.getRiskEventId(), e.getTenantId(), e.getRiskLevel(), e.getRiskType(),
                        e.getStatus(), e.getDetectedAt(), e.getNotifyStatus()))
                .toList();
    }

    /** 逾期清单脱敏条目（R-7：学生级明细仅 super_admin/audit） */
    public record OverdueEntry(
            java.util.UUID riskEventId,
            java.util.UUID tenantId,
            Integer riskLevel,
            String riskType,
            String status,
            Instant detectedAt,
            String notifyStatus) {
    }

    /** 转派：更新负责人 + 留痕（action=transfer，operator 必填）；仅 open/claimed 可转派（L1） */
    public void transfer(UUID riskEventId, UUID assignToUserId, String operator, String detail) {
        RiskEvent event = requireEvent(riskEventId);
        if (!RiskEvent.STATUS_OPEN.equals(event.getStatus()) && !RiskEvent.STATUS_CLAIMED.equals(event.getStatus())) {
            throw new com.mindsafe.common.exception.BizException(
                    com.mindsafe.common.dto.ErrorCode.PARAM_INVALID,
                    "仅未处置（open/claimed）事件可转派，当前状态: " + event.getStatus());
        }
        event.setAssignedUserId(assignToUserId);
        event.setStatus(RiskEvent.STATUS_CLAIMED);
        riskEventMapper.updateById(event);
        writeLog(event, SlaEscalationLog.ACTION_TRANSFER, operator, detail);
    }

    /** 强制关闭：置 closed + 留痕（action=force_close，operator 必填） */
    public void forceClose(UUID riskEventId, String operator, String detail) {
        RiskEvent event = requireEvent(riskEventId);
        event.setStatus(RiskEvent.STATUS_CLOSED);
        event.setClosedAt(Instant.now());
        riskEventMapper.updateById(event);
        writeLog(event, SlaEscalationLog.ACTION_FORCE_CLOSE, operator, detail);
    }

    private RiskEvent requireEvent(UUID riskEventId) {
        RiskEvent event = TenantContextHolder.callAsSystem(() -> riskEventMapper.selectById(riskEventId));
        if (event == null) {
            throw new com.mindsafe.common.exception.BizException(
                    com.mindsafe.common.dto.ErrorCode.RESOURCE_NOT_FOUND, "预警事件不存在: " + riskEventId);
        }
        return event;
    }

    private void writeLog(RiskEvent event, String action, String operator, String detail) {
        SlaEscalationLog log = new SlaEscalationLog();
        log.setEscalationId(UUID.randomUUID());
        log.setRiskEventId(event.getRiskEventId());
        log.setStage("ack");
        log.setEscalatedAt(Instant.now());
        log.setAction(action);
        log.setOperator(operator);
        log.setDetail(detail);
        slaEscalationLogMapper.insert(log);
    }

    private String levelName(Integer level) {
        if (level == null) {
            return "unknown";
        }
        return switch (level) {
            case 3 -> "red";
            case 2 -> "orange";
            case 1 -> "yellow";
            default -> "green";
        };
    }
}
