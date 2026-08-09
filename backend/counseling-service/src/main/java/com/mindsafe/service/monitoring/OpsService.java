package com.mindsafe.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
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
    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${mindsafe.monitoring.alertmanager-url:http://alertmanager:9093}")
    private String alertmanagerUrl;

    public OpsService(ServiceHealthProbe probe,
                      ServiceHealthSnapshotMapper snapshotMapper,
                      AuditLogMapper auditLogMapper) {
        this.probe = probe;
        this.snapshotMapper = snapshotMapper;
        this.auditLogMapper = auditLogMapper;
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

    /** 采集外呼必须带超时：AlertManager 不可达时不能挂死请求线程（WeComAlertService 同模式） */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
