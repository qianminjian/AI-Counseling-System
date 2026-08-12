package com.mindsafe.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 会话摘要补偿任务（BUG-T-04-01，2026-08-12，UI-TEST-013）。
 * <p>
 * 背景：escalated 红色风险会话（升级转人工链路）摘要 3 天未生成——结束事件未触发
 * 异步摘要或 LLM 调用失败时，摘要永久缺失。本任务周期性扫描终态会话中
 * session_summary 仍为空且结束超过 5 分钟的记录，重新触发异步生成（幂等：
 * 空转写跳过 / 已生成后不再为空）。
 * <p>
 * 系统作用域执行（跨租户扫描），扫描上限 50 条/轮防堆积。
 */
@Component
public class SummaryCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(SummaryCompensationJob.class);

    /** 终态判定延迟：会话结束至少 5 分钟才进入补偿候选（给异步任务正常完成窗口） */
    private static final long SETTLE_MINUTES = 5;
    /** 每轮补偿上限（防 LLM 突发堆积） */
    private static final int SCAN_LIMIT = 50;

    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryService messageSummaryService;

    public SummaryCompensationJob(CounselingSessionMapper sessionMapper,
                                  MessageSummaryService messageSummaryService) {
        this.sessionMapper = sessionMapper;
        this.messageSummaryService = messageSummaryService;
    }

    @Scheduled(cron = "${mindsafe.summary-compensation.scan-cron:0 */10 * * * ?}")
    public void compensate() {
        TenantContextHolder.runAsSystem(() -> {
            try {
                Instant cutoff = Instant.now().minus(SETTLE_MINUTES, ChronoUnit.MINUTES);
                // BUG-T-04-01 四次修复（2026-08-12 复测）：会话终态枚举含 escalated（风险升级转人工，
                // ConversationServiceImpl 置 escalated）+ taken_over（教师接管）+ completed——
                // 三者均无后续对话，缺摘要即补偿；endedAt 可能为 null（escalated/taken_over 不设），
                // 终态判定：结束超 5 分钟 或 未记录结束时间。
                List<CounselingSession> sessions = sessionMapper.selectList(
                        new LambdaQueryWrapper<CounselingSession>()
                                .in(CounselingSession::getSessionStatus,
                                        CounselingSession.STATUS_COMPLETED, "taken_over", "escalated")
                                .isNull(CounselingSession::getSessionSummary)
                                .and(w -> w.lt(CounselingSession::getEndedAt, cutoff)
                                        .or().isNull(CounselingSession::getEndedAt))
                                .orderByAsc(CounselingSession::getEndedAt)
                                .last("LIMIT " + SCAN_LIMIT));
                if (sessions.isEmpty()) {
                    return;
                }
                log.info("摘要补偿：发现 {} 条缺失摘要的终态会话，重新触发异步生成", sessions.size());
                for (CounselingSession s : sessions) {
                    // 幂等：generateSummaryAsync 内部空转写跳过；生成成功后 session_summary 非空不再命中
                    messageSummaryService.generateSummaryAsync(
                            s.getTenantId(), s.getSessionId(), s.getStudentUserId());
                }
            } catch (Exception e) {
                log.warn("摘要补偿扫描失败（不影响业务）: {}", e.getMessage());
            }
        });
    }
}
