package com.mindsafe.service.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 数据保留期定期清理任务（AUTH-031，PIPL 数据最小化 + 删除权）
 * <p>
 * 保留策略（对齐 design/14 §8 + design/12 数据保留策略）：
 * <ul>
 *   <li>普通对话消息摘要：180 天后物理删除（默认，可配置 normal-session-days）</li>
 *   <li>高风险对话消息摘要（risk_level ≥ 2）：365 天后物理删除（默认，可配置 high-risk-session-days）</li>
 *   <li>已完成会话记录：跟随消息保留期（普通 180 天 / 高风险 365 天）</li>
 * </ul>
 * 每日凌晨 03:00 执行（低峰期），清理结果写入审计日志。
 */
@Service
public class DataRetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionCleanupJob.class);

    /** 高风险阈值：risk_level >= 此值视为高风险，适用长保留期 */
    private static final int HIGH_RISK_THRESHOLD = 2;

    private final MessageSummaryMapper messageSummaryMapper;
    private final CounselingSessionMapper sessionMapper;
    private final AuditLogService auditLogService;

    private final int normalRetentionDays;
    private final int highRiskRetentionDays;

    public DataRetentionCleanupJob(
            MessageSummaryMapper messageSummaryMapper,
            CounselingSessionMapper sessionMapper,
            AuditLogService auditLogService,
            @Value("${mindsafe.security.data-retention.normal-session-days:180}") int normalRetentionDays,
            @Value("${mindsafe.security.data-retention.high-risk-session-days:365}") int highRiskRetentionDays) {
        this.messageSummaryMapper = messageSummaryMapper;
        this.sessionMapper = sessionMapper;
        this.auditLogService = auditLogService;
        this.normalRetentionDays = normalRetentionDays;
        this.highRiskRetentionDays = highRiskRetentionDays;
    }

    /**
     * 每日凌晨 03:00 执行数据保留期清理。
     */
    @Scheduled(cron = "${mindsafe.security.data-retention.cleanup-cron:0 0 3 * * ?}")
    public void executeCleanup() {
        // 全租户清理属合法跨租户链路：显式声明系统作用域（M1-003 fail-fast 配套）
        TenantContextHolder.runAsSystem(this::doCleanup);
    }

    private void doCleanup() {
        log.info("数据保留期清理任务开始: normalDays={}, highRiskDays={}", normalRetentionDays, highRiskRetentionDays);

        Instant normalCutoff = Instant.now().minus(normalRetentionDays, ChronoUnit.DAYS);
        Instant highRiskCutoff = Instant.now().minus(highRiskRetentionDays, ChronoUnit.DAYS);

        int deletedMessages = 0;
        int deletedSessions = 0;

        try {
            // 1. 清理普通消息摘要（risk_level < 2 且超过 180 天）
            deletedMessages += messageSummaryMapper.delete(
                    new LambdaQueryWrapper<MessageSummary>()
                            .lt(MessageSummary::getCreatedAt, normalCutoff)
                            .and(w -> w
                                    .lt(MessageSummary::getRiskLevel, HIGH_RISK_THRESHOLD)
                                    .or()
                                    .isNull(MessageSummary::getRiskLevel)
                            )
            );

            // 2. 清理高风险消息摘要（risk_level >= 2 且超过 365 天）
            deletedMessages += messageSummaryMapper.delete(
                    new LambdaQueryWrapper<MessageSummary>()
                            .lt(MessageSummary::getCreatedAt, highRiskCutoff)
                            .ge(MessageSummary::getRiskLevel, HIGH_RISK_THRESHOLD)
            );

            // 3. 清理已完成的普通会话（risk_level_snapshot < 2 且超过 180 天）
            deletedSessions += sessionMapper.delete(
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getSessionStatus, CounselingSession.STATUS_COMPLETED)
                            .lt(CounselingSession::getCreatedAt, normalCutoff)
                            .and(w -> w
                                    .lt(CounselingSession::getRiskLevelSnapshot, HIGH_RISK_THRESHOLD)
                                    .or()
                                    .isNull(CounselingSession::getRiskLevelSnapshot)
                            )
            );

            // 4. 清理已完成的高风险会话（risk_level_snapshot >= 2 且超过 365 天）
            deletedSessions += sessionMapper.delete(
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getSessionStatus, CounselingSession.STATUS_COMPLETED)
                            .lt(CounselingSession::getCreatedAt, highRiskCutoff)
                            .ge(CounselingSession::getRiskLevelSnapshot, HIGH_RISK_THRESHOLD)
            );

            log.info("数据保留期清理完成: 删除消息摘要 {} 条, 删除会话 {} 条", deletedMessages, deletedSessions);

            // 5. 审计日志（系统级，tenantId=null 表示全局操作）
            auditLogService.log(null, null, "DATA_RETENTION_CLEANUP", "system", null,
                    String.format("定期清理: 删除消息摘要 %d 条, 删除会话 %d 条 (普通保留%d天/高风险保留%d天)",
                            deletedMessages, deletedSessions, normalRetentionDays, highRiskRetentionDays));

        } catch (Exception e) {
            log.error("数据保留期清理任务异常", e);
            auditLogService.log(null, null, "DATA_RETENTION_CLEANUP_ERROR", "system", null,
                    "清理任务异常: " + e.getMessage());
        }
    }
}
