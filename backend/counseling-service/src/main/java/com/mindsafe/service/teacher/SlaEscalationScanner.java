package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.SysConfig;
import com.mindsafe.domain.mapper.SysConfigMapper;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.SlaEscalationLog;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.SlaEscalationLogMapper;
import com.mindsafe.service.alert.AlertService;
import com.mindsafe.service.alert.AlertService.AlertLevel;
import com.mindsafe.service.teacher.AlertSlaPolicy.SlaDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预警 SLA 超时兜底扫描（P-05，WB-001）
 * <p>
 * 安全兜底铁律：红色风险「5 分钟必须有人接住」。{@link AlertSlaPolicy} 是纯函数策略，
 * 本类是其唯一的定时接线：周期性扫描未处理的风险事件，检测 SLA 超时并触发升级/提醒告警。
 * <ul>
 *   <li>open 且超时（RED 5min / ORANGE 15min）→ ESCALATE → {@link AlertLevel#CRITICAL} 告警</li>
 *   <li>claimed 但超时未处理完 / YELLOW 超时 → REMIND → {@link AlertLevel#WARNING} 告警</li>
 *   <li>resolved / closed / GREEN → 不评估</li>
 * </ul>
 * <p>
 * 去重：同一事件在冷却期（{@code re-alert-cooldown-minutes}）内只告警一次，避免告警风暴；
 * 已解决/关闭的事件（不再命中扫描）会从内存去重表中剔除，防止无界增长。
 * <p>
 * <b>多租户说明</b>：当前为「共享 tenant_template schema + 行级 tenant_id」模型（见 design/07 §11），
 * 单次全局扫描即覆盖所有租户；{@link AlertService} 为全局运维告警出口，告警详情内嵌 tenantId/studentUserId。
 * 待 fix-06 启用 Schema 级隔离后，此处需改为按租户遍历切换 search_path。
 */
@Service
public class SlaEscalationScanner {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationScanner.class);

    /** 有 SLA 的最低风险等级（GREEN=0 无 SLA，YELLOW=1 起才评估） */
    private static final int SLA_MIN_RISK_LEVEL = 1;

    private final RiskEventMapper riskEventMapper;
    private final AlertSlaPolicy slaPolicy;
    private final AlertService alertService;

    private final boolean enabled;
    private final int reAlertCooldownMinutes;

    /** 内存去重表：riskEventId -> 最近一次告警时间（每次扫描按当前命中集合剪枝，避免无界增长） */
    private final Map<UUID, Instant> lastAlertAt = new ConcurrentHashMap<>();

    /** OPS-MON-008 同源 Mapper：升级留痕（ADMIN-P1-05：sla_escalation_log） */
    private final SlaEscalationLogMapper slaEscalationLogMapper;

    /** AUDIT-DEEP-003（P2-01）：sys_config 运行时消费——V38 种子键 mindsafe.security.sla-escalation.enabled（HOT，DB 覆盖 yml 默认） */
    private static final String SYS_CONFIG_ENABLED_KEY = "mindsafe.security.sla-escalation.enabled";

    private final SysConfigMapper sysConfigMapper;

    public SlaEscalationScanner(
            RiskEventMapper riskEventMapper,
            AlertSlaPolicy slaPolicy,
            AlertService alertService,
            SlaEscalationLogMapper slaEscalationLogMapper,
            SysConfigMapper sysConfigMapper,
            @Value("${mindsafe.security.sla-escalation.enabled:true}") boolean enabled,
            @Value("${mindsafe.security.sla-escalation.re-alert-cooldown-minutes:60}") int reAlertCooldownMinutes) {
        this.riskEventMapper = riskEventMapper;
        this.slaPolicy = slaPolicy;
        this.alertService = alertService;
        this.slaEscalationLogMapper = slaEscalationLogMapper;
        this.sysConfigMapper = sysConfigMapper;
        this.enabled = enabled;
        this.reAlertCooldownMinutes = reAlertCooldownMinutes;
    }

    /**
     * 每分钟扫描一次未处理的风险事件，检测 SLA 超时并升级。
     * AUDIT-DEEP-003：运行时开关 = sys_config（DB，HOT 键）优先于 yml 默认——
     * 管理端配置注册表修改立即生效（false 暂停扫描，true 恢复）。
     */
    @Scheduled(cron = "${mindsafe.security.sla-escalation.scan-cron:0 * * * * ?}")
    public void scan() {
        // 并发互斥（code-review L2）：扫描重叠会双发告警 + 双写留痕，重叠直接跳过
        synchronized (this) {
            if (!isEnabled()) {
                return;
            }
            TenantContextHolder.runAsSystem(this::doScan);
        }
    }

    /** 运行时启用判定：sys_config 键存在 → DB 值（HOT 覆盖）；键缺失/异常 → yml 默认（fail-open 不阻断扫描） */
    private boolean isEnabled() {
        try {
            SysConfig config = TenantContextHolder.callAsSystem(() ->
                    sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                            .eq(SysConfig::getConfigKey, SYS_CONFIG_ENABLED_KEY)));
            if (config != null && config.getValue() != null) {
                String v = config.getValue().trim();
                // P2-1（code-review）：非法值告警并回落 yml（fail-open），而非 parseBoolean 静默 false 关闭安全兜底
                if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
                    return Boolean.parseBoolean(v);
                }
                log.warn("sys_config 键 {} 值非法（{}），回落 yml 默认: {}", SYS_CONFIG_ENABLED_KEY, v, enabled);
            }
        } catch (Exception e) {
            log.warn("sys_config 读取失败，回落 yml 默认: {}", e.getMessage());
        }
        return enabled;
    }

    private void doScan() {
        Instant now = Instant.now();
        try {
            List<RiskEvent> candidates = riskEventMapper.selectList(
                    new LambdaQueryWrapper<RiskEvent>()
                            .in(RiskEvent::getStatus, List.of(RiskEvent.STATUS_OPEN, RiskEvent.STATUS_CLAIMED))
                            .ge(RiskEvent::getRiskLevel, SLA_MIN_RISK_LEVEL));

            Set<UUID> breachedIds = new HashSet<>();
            int escalated = 0;
            int reminded = 0;

            for (RiskEvent e : candidates) {
                Instant createdAt = e.getCreatedAt() != null ? e.getCreatedAt() : e.getDetectedAt();
                if (createdAt == null) {
                    continue;
                }
                SlaDecision decision = slaPolicy.evaluate(toPolicyLevel(e.getRiskLevel()), e.getStatus(), createdAt, now);
                if (!decision.breached()) {
                    continue;
                }
                breachedIds.add(e.getRiskEventId());

                // 冷却期内已告警过 → 跳过，避免风暴
                Instant last = lastAlertAt.get(e.getRiskEventId());
                if (last != null && Duration.between(last, now).toMinutes() < reAlertCooldownMinutes) {
                    continue;
                }

                if (decision.escalate()) {
                    alertService.sendAlert(AlertLevel.CRITICAL, "风险预警 SLA 超时未接管（升级）", buildDetail(e, decision));
                    escalated++;
                    // ADMIN-P1-05：升级留痕（sla_escalation_log，平台逾期清单数据源）
                    recordEscalation(e, decision);
                } else {
                    alertService.sendAlert(AlertLevel.WARNING, "风险预警 SLA 超时（提醒）", buildDetail(e, decision));
                    reminded++;
                }
                lastAlertAt.put(e.getRiskEventId(), now);
            }

            // 剪枝：已解决/关闭而不再命中扫描的事件从去重表移除
            lastAlertAt.keySet().retainAll(breachedIds);

            if (escalated + reminded > 0) {
                log.warn("SLA 超时扫描: 升级 {} 起, 提醒 {} 起 (命中超时 {} 起)", escalated, reminded, breachedIds.size());
            }
        } catch (Exception ex) {
            // 定时任务不得因单次异常中断
            log.error("SLA 超时扫描任务异常", ex);
        }
    }

    /**
     * 风险等级数值 → SLA 策略等级字符串。
     */
    private String toPolicyLevel(Integer riskLevel) {
        if (riskLevel == null) {
            return "GREEN";
        }
        return switch (riskLevel) {
            case 3 -> "RED";
            case 2 -> "ORANGE";
            case 1 -> "YELLOW";
            default -> "GREEN";
        };
    }

    private String buildDetail(RiskEvent e, SlaDecision decision) {
        return String.format(
                "风险事件 %s 已超 SLA %d 分钟未处理。租户=%s, 学生=%s, 类型=%s, 等级=%s, 状态=%s, 建议=%s",
                e.getRiskEventId(), decision.overdueMinutes(), e.getTenantId(), e.getStudentUserId(),
                e.getRiskType(), toPolicyLevel(e.getRiskLevel()), e.getStatus(), decision.action());
    }

    /**
     * ADMIN-P1-05：升级留痕（sla_escalation_log；自动升级 stage=ack，operator 为空）。
     * 附加通道原则（2026-08-10）：主键由代码生成（IdType.INPUT 实体，缺则 INSERT NULL 违反
     * NOT NULL）；留痕失败仅记 WARN，不中断 SLA 扫描主流程（告警已发，台账缺失可补查）。
     */
    private void recordEscalation(RiskEvent e, SlaDecision decision) {
        try {
            SlaEscalationLog log = new SlaEscalationLog();
            log.setEscalationId(UUID.randomUUID());
            log.setRiskEventId(e.getRiskEventId());
            log.setStage("ack");
            log.setEscalatedAt(Instant.now());
            log.setAction(SlaEscalationLog.ACTION_NOTIFY_ESCALATE);
            log.setDetail(String.format("SLA 超时自动升级（action=%s, overdue=%dmin）", decision.action(), decision.overdueMinutes()));
            slaEscalationLogMapper.insert(log);
        } catch (Exception ex) {
            log.warn("SLA 升级留痕落库失败: riskEventId={}, error={}", e.getRiskEventId(), ex.getMessage());
        }
    }
}
