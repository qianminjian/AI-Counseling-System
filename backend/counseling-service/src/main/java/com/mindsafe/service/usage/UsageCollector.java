package com.mindsafe.service.usage;

import com.mindsafe.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 计量采集器（ADMIN-P3-01，M4 采集层——计量非计费，DEC-007 先行）
 * <p>
 * - llm_call：每 30min 聚合 model_call_logs 近窗口 token 数写入 usage_events（按租户）
 * - active_student_snapshot：每日 00:30 写入当日活跃学生快照
 * 幂等：UNIQUE(metric, tenant_id, event_time) + ON CONFLICT DO NOTHING（同窗口重复执行不重复计）。
 * 设计见 doing/83 §6.6。
 */
@Component
public class UsageCollector {

    private static final Logger log = LoggerFactory.getLogger(UsageCollector.class);

    /** 聚合窗口（分钟） */
    private static final int LLM_WINDOW_MINUTES = 30;

    private final JdbcTemplate jdbcTemplate;

    public UsageCollector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** LLM 调用聚合（30min 窗口，按租户 token 总和） */
    // 修复（2026-08-10）：7 段 Quartz 格式（0 */30 * * * * ?）非法——Spring @Scheduled 为 6 段
    // （秒 分 时 日 月 周），多一个 * 导致启动失败（BeanCreationException）
    @Scheduled(cron = "${mindsafe.monitoring.usage-collector.llm-cron:0 */30 * * * *}")
    public void collectLlmCalls() {
        TenantContextHolder.runAsSystem(() -> {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES)
                    .minus(LLM_WINDOW_MINUTES, ChronoUnit.MINUTES);
            int rows = jdbcTemplate.update("""
                    INSERT INTO tenant_template.usage_events (tenant_id, metric, value, unit, event_time)
                    SELECT tenant_id, 'llm_call', COALESCE(SUM(total_tokens), 0), 'token', ?
                    FROM tenant_template.model_call_logs
                    WHERE created_at >= ? AND created_at < ? AND status = 'success'
                    GROUP BY tenant_id
                    ON CONFLICT (metric, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), event_time)
                    DO NOTHING
                    """, windowStart, windowStart, windowStart.plus(LLM_WINDOW_MINUTES, ChronoUnit.MINUTES));
            log.debug("LLM 用量聚合完成: 窗口 {}，写入 {} 条", windowStart, rows);
        });
    }

    /** 活跃学生快照（每日 00:30：当日有会话的去重学生数，按租户） */
    @Scheduled(cron = "${mindsafe.monitoring.usage-collector.snapshot-cron:0 30 0 * * ?}")
    public void collectActiveStudents() {
        TenantContextHolder.runAsSystem(() -> {
            Instant dayStart = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                    .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            int rows = jdbcTemplate.update("""
                    INSERT INTO tenant_template.usage_events (tenant_id, metric, value, unit, event_time)
                    SELECT tenant_id, 'active_student_snapshot', COUNT(DISTINCT student_user_id), 'count', ?
                    FROM tenant_template.counseling_sessions
                    WHERE started_at >= ? AND started_at < ?
                    GROUP BY tenant_id
                    ON CONFLICT (metric, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), event_time)
                    DO NOTHING
                    """, dayStart, dayStart, dayStart.plus(1, ChronoUnit.DAYS));
            log.info("活跃学生快照完成: {}，写入 {} 条", dayStart, rows);
        });
    }
}
