package com.mindsafe.service.safety;

import com.mindsafe.ai.memory.ChatMemoryAppender;
import com.mindsafe.ai.safety.OutputSafetyReporter;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 输出安全违规上报实现（依赖倒置 adapter，实现 counseling-ai 定义的 {@link OutputSafetyReporter}）。
 * <p>
 * 复用现有 {@code tenant_template.risk_events} 表（V46 新增 review_json 列）：
 * <ul>
 *   <li>Layer1 实时拦截（严重）：risk_level=RED(3)，detected_by=output_filter，触发教师通知（与输入风险一致）；</li>
 *   <li>Layer2 异步审查留痕（低危）：risk_level=YELLOW(1)，detected_by=output_review，仅留痕不通知（避免轰炸教师）；</li>
 *   <li>Layer2 召回替换（SAFE-202）：追加更正消息至已落记忆（原子追加，不做整表替换——
 *       幂等无并发覆盖），分级 rewrite=YELLOW/block=ORANGE/escalate=RED，
 *       block/escalate 触发教师通知。</li>
 * </ul>
 * <p>
 * reviewJson（LLM 审查 JSON）随事件落库（doing/92 R-015，V46 review_json 列），供 TC260 人工抽检回溯。
 * 会话查不到时优雅降级（仅日志，不抛异常）——上报失败绝不影响对话主流程。
 */
@Service
public class OutputSafetyReporterImpl implements OutputSafetyReporter {

    private static final Logger log = LoggerFactory.getLogger(OutputSafetyReporterImpl.class);

    /** 输出安全违规统一 risk_type 前缀 */
    private static final String RISK_TYPE_OUTPUT_SAFETY = "output_safety";

    private final CounselingSessionMapper sessionMapper;
    private final RiskEventMapper riskEventMapper;
    private final NotificationService notificationService;
    private final RiskNotifyOutboxService riskNotifyOutboxService;
    private final ChatMemoryAppender chatMemoryAppender;

    public OutputSafetyReporterImpl(CounselingSessionMapper sessionMapper,
                                    RiskEventMapper riskEventMapper,
                                    NotificationService notificationService,
                                    RiskNotifyOutboxService riskNotifyOutboxService,
                                    ChatMemoryAppender chatMemoryAppender) {
        this.sessionMapper = sessionMapper;
        this.riskEventMapper = riskEventMapper;
        this.notificationService = notificationService;
        this.riskNotifyOutboxService = riskNotifyOutboxService;
        this.chatMemoryAppender = chatMemoryAppender;
    }

    @Override
    public void reportLayer1Block(UUID sessionId, String category, String matchedKeyword, String snippet) {
        try {
            CounselingSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                log.warn("Layer1 上报跳过（会话不存在）: sessionId={}, category={}", sessionId, category);
                return;
            }
            RiskEvent event = RiskEvent.fromDetection(
                    session.getTenantId(),
                    session.getStudentUserId(),
                    sessionId,
                    RISK_TYPE_OUTPUT_SAFETY + ":" + category,
                    RiskLevel.RED.severity()
            );
            event.setDetectedBy("output_filter");
            riskEventMapper.insert(event);

            // 严重违规：与输入风险一致，触发教师通知；失败进 outbox 补偿队列（P0-4）
            try {
                notificationService.notifyRiskEvent(event);
                riskNotifyOutboxService.markSent(event);
            } catch (Exception e) {
                log.error("Layer1 教师通知失败(已标记 failed 进补偿队列): riskEventId={}", event.getRiskEventId(), e);
                riskNotifyOutboxService.markFailed(event);
            }

            log.warn("Layer1 输出违规已记录: sessionId={}, category={}, keyword={}, riskEventId={}",
                    sessionId, category, matchedKeyword, event.getRiskEventId());
        } catch (Exception e) {
            log.error("Layer1 违规持久化失败（不影响对话流）: sessionId={}", sessionId, e);
        }
    }

    @Override
    public void reportLayer2Violation(UUID sessionId, String decision, String reviewJson) {
        try {
            CounselingSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                log.warn("Layer2 上报跳过（会话不存在）: sessionId={}, decision={}", sessionId, decision);
                return;
            }
            RiskEvent event = RiskEvent.fromDetection(
                    session.getTenantId(),
                    session.getStudentUserId(),
                    sessionId,
                    RISK_TYPE_OUTPUT_SAFETY,
                    RiskLevel.YELLOW.severity()
            );
            event.setDetectedBy("output_review");
            // doing/92 R-015：审查 JSON 落库（TC260 人工抽检依据）
            event.setReviewJson(reviewJson);
            riskEventMapper.insert(event);

            // P0-4：无通知义务的事件标记完成态，防止补偿任务误重试留痕事件
            riskNotifyOutboxService.markSent(event);

            // 低危留痕：不触发教师通知，供人工抽检复核（对齐 TC260 人工抽检机制）
            log.info("Layer2 输出违规已记录: sessionId={}, decision={}, riskEventId={}",
                    sessionId, decision, event.getRiskEventId());
        } catch (Exception e) {
            log.error("Layer2 违规持久化失败（不影响对话流）: sessionId={}", sessionId, e);
        }
    }

    @Override
    public void applyLayer2Recall(UUID sessionId, String decision, String replacementText, String reviewJson) {
        try {
            CounselingSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                log.warn("Layer2 召回跳过（会话不存在）: sessionId={}, decision={}", sessionId, decision);
                return;
            }

            // 1. 追加更正消息（doing/92 R-015：替换改追加——原子幂等无并发覆盖；
            //    SSE 单向流无实时召回通道，下一轮历史即更正后版本。
            //    设计权衡：违规原文仍留在记忆（学生已在 SSE 见过），后续 LLM 上下文携带
            //    原文+更正，由输出过滤器（Layer1/Layer2）二次拦截兜底——纵深防御不破）
            //    记忆为空（TTL 过期/从未写入）时跳过追加，避免悬空更正成为整段历史
            if (chatMemoryAppender.hasMessages(sessionId.toString())) {
                chatMemoryAppender.append(sessionId.toString(), new AssistantMessage(replacementText));
            } else {
                log.warn("Layer2 召回：会话记忆为空，跳过追加更正消息: sessionId={}, decision={}",
                        sessionId, decision);
            }

            // 2. 分级留痕：rewrite=YELLOW 不通知；block=ORANGE、escalate=RED 通知教师
            RiskLevel level = switch (decision) {
                case "block" -> RiskLevel.ORANGE;
                case "escalate" -> RiskLevel.RED;
                default -> RiskLevel.YELLOW;
            };
            RiskEvent event = RiskEvent.fromDetection(
                    session.getTenantId(),
                    session.getStudentUserId(),
                    sessionId,
                    RISK_TYPE_OUTPUT_SAFETY + ":recall:" + decision,
                    level.severity()
            );
            event.setDetectedBy("output_review");
            // doing/92 R-015：审查 JSON 落库（TC260 人工抽检依据）
            event.setReviewJson(reviewJson);
            riskEventMapper.insert(event);

            if (level != RiskLevel.YELLOW) {
                // block/escalate：通知教师，失败进 outbox 补偿队列（P0-4）
                try {
                    notificationService.notifyRiskEvent(event);
                    riskNotifyOutboxService.markSent(event);
                } catch (Exception e) {
                    log.error("Layer2 召回教师通知失败(已标记 failed 进补偿队列): riskEventId={}", event.getRiskEventId(), e);
                    riskNotifyOutboxService.markFailed(event);
                }
            } else {
                // rewrite：无通知义务 → 标记完成态，防止补偿任务误重试
                riskNotifyOutboxService.markSent(event);
            }

            log.warn("Layer2 召回已执行: sessionId={}, decision={}, replaced=true, riskEventId={}",
                    sessionId, decision, event.getRiskEventId());
        } catch (Exception e) {
            log.error("Layer2 召回执行失败（不影响对话流）: sessionId={}, decision={}", sessionId, decision, e);
        }
    }
}