package com.mindsafe.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.AlertEvent;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.domain.mapper.AlertEventMapper;
import com.mindsafe.domain.mapper.AuditLogMapper;
import com.mindsafe.domain.mapper.ServiceHealthSnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运维域服务（ADMIN-P0-05/06/07，M2 服务拓扑/告警只读 + M6 审计跨租户查询）
 * <p>
 * 服务拓扑实时状态（探针）与快照历史、AlertManager 告警只读代理、跨租户审计
 * 检索（tenantId 可空 = 平台级全量）。设计见 doing/83 后台管理端 §5.2/§7.2。
 */
@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    private final ServiceHealthProbe probe;
    private final ServiceHealthSnapshotMapper snapshotMapper;
    private final AuditLogMapper auditLogMapper;
    private final AlertEventMapper alertEventMapper;
    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${mindsafe.monitoring.alertmanager-url:http://alertmanager:9093}")
    private String alertmanagerUrl;

    public OpsService(ServiceHealthProbe probe,
                      ServiceHealthSnapshotMapper snapshotMapper,
                      AuditLogMapper auditLogMapper,
                      AlertEventMapper alertEventMapper) {
        this.probe = probe;
        this.snapshotMapper = snapshotMapper;
        this.auditLogMapper = auditLogMapper;
        this.alertEventMapper = alertEventMapper;
    }

    /** 六服务实时健康状态 */
    public Map<String, String> servicesStatus() {
        return probe.probeAll();
    }

    /** 服务健康快照历史（按服务 + 时间倒序，limit 默认 100） */
    public List<ServiceHealthSnapshot> healthHistory(String service, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return snapshotMapper.selectList(new LambdaQueryWrapper<ServiceHealthSnapshot>()
                .eq(service != null && !service.isBlank(), ServiceHealthSnapshot::getService, service)
                .orderByDesc(ServiceHealthSnapshot::getSampledAt)
                .last("LIMIT " + safeLimit));
    }

    /** AlertManager active 告警只读代理（P0-06：直读不落库，历史落库属 OPS-MON-008） */
    public List<Map<String, Object>> activeAlerts() {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> alerts =
                    restTemplate.getForObject(alertmanagerUrl + "/api/v2/alerts", List.class);
            return alerts == null ? List.of() : alerts;
        } catch (Exception e) {
            log.warn("告警只读拉取 AlertManager 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 跨租户审计检索（P0-07）：tenantId 可空 = 平台级全量；action 精确过滤；时间范围筛选。
     * 平台 token 无租户上下文，audit_logs 为租户表（fail-fast 拦截）——callAsSystem 显式声明
     * 系统作用域（安全边界由 /api/v1/ops/** 平台角色授权兜底，code-review H1）。
     */
    public List<AuditLog> auditLogs(UUID tenantId, String action, Instant startTime, Instant endTime, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return TenantContextHolder.callAsSystem(() ->
                auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                        .eq(tenantId != null, AuditLog::getTenantId, tenantId)
                        .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                        .ge(startTime != null, AuditLog::getCreatedAt, startTime)
                        .le(endTime != null, AuditLog::getCreatedAt, endTime)
                        .orderByDesc(AuditLog::getCreatedAt)
                        .last("LIMIT " + safeLimit)));
    }

    // ===== M2 告警事件中心（ADMIN-P1-08：alert_events 落库消费 + ack） =====

    /**
     * 告警事件历史列表（source=alertmanager + alertservice 聚合台账）。
     * 平台级表（tenant_template）无租户上下文——callAsSystem 显式声明系统作用域。
     */
    public List<AlertEvent> alertEvents(String status, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return TenantContextHolder.callAsSystem(() ->
                alertEventMapper.selectList(new LambdaQueryWrapper<AlertEvent>()
                        .eq(status != null && !status.isBlank(), AlertEvent::getStatus, status)
                        .orderByDesc(AlertEvent::getFiredAt)
                        .last("LIMIT " + safeLimit)));
    }

    /**
     * 告警确认（firing → ack，仅 ops/super，SecurityConfig 强制）。
     * 已 ack/closed 的记录重复 ack 幂等跳过（状态机：firing→ack→closed 单向推进）。
     * 条件更新（code-review L2）：仅当 status=firing 才回写，消除与采集器 resolved 流转的极窄竞态。
     * 主操作语义（code-review M3）：落库失败向上抛，前端可见失败而非误报成功。
     */
    public void ackAlert(UUID eventId, String operator, String reason) {
        TenantContextHolder.callAsSystem(() -> {
            AlertEvent event = alertEventMapper.selectById(eventId);
            if (event == null) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "告警事件不存在: " + eventId);
            }
            if (AlertEvent.STATUS_ACK.equals(event.getStatus())
                    || AlertEvent.STATUS_CLOSED.equals(event.getStatus())) {
                return null; // 已确认/已关闭：幂等跳过
            }
            AlertEvent update = new AlertEvent();
            update.setStatus(AlertEvent.STATUS_ACK);
            update.setAcknowledgedBy(operator);
            update.setAcknowledgedAt(Instant.now());
            update.setAckReason(reason); // code-review H1：确认原因审计留痕
            int updated = alertEventMapper.update(update,
                    new LambdaUpdateWrapper<AlertEvent>()
                            .eq(AlertEvent::getEventId, eventId)
                            .eq(AlertEvent::getStatus, AlertEvent.STATUS_FIRING));
            if (updated == 0) {
                log.warn("告警 ack 条件更新未命中（状态已变化）: eventId={}", eventId);
            }
            return null;
        });
    }

    /** 采集外呼必须带超时：AlertManager 不可达时不能挂死请求线程（WeComAlertService 同模式） */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
