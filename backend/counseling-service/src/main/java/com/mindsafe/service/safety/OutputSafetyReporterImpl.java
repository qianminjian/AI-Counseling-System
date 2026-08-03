package com.mindsafe.service.safety;

import com.mindsafe.ai.safety.OutputSafetyReporter;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 输出安全违规上报实现（依赖倒置 adapter，实现 counseling-ai 定义的 {@link OutputSafetyReporter}）。
 * <p>
 * 复用现有 {@code tenant_template.risk_events} 表（无 schema 变更）：
 * <ul>
 *   <li>Layer1 实时拦截（严重）：risk_level=RED(3)，detected_by=output_filter，触发教师通知（与输入风险一致）；</li>
 *   <li>Layer2 异步审查留痕（低危）：risk_level=YELLOW(1)，detected_by=output_review，仅留痕不通知（避免轰炸教师）；</li>
 *   <li>Layer2 召回替换（SAFE-202）：替换已落记忆的 AI 回复，分级 rewrite=YELLOW/block=ORANGE/escalate=RED，
 *       block/escalate 触发教师通知。</li>
 * </ul>
 * <p>
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
    private final ChatMemoryRepository chatMemoryRepository;

    public OutputSafetyReporterImpl(CounselingSessionMapper sessionMapper,
                                    RiskEventMapper riskEventMapper,
                                    NotificationService notificationService,
                                    ChatMemoryRepository chatMemoryRepository) {
        this.sessionMapper = sessionMapper;
        this.riskEventMapper = riskEventMapper;
        this.notificationService = notificationService;
        this.chatMemoryRepository = chatMemoryRepository;
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

            // 严重违规：与输入风险一致，触发教师通知
            notificationService.notifyRiskEvent(event);

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
            riskEventMapper.insert(event);

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

            // 1. 替换已落记忆的最后一条 AI 回复（SSE 单向流无实时召回通道，下一轮历史即替换后版本）
            boolean replaced = replaceLastAssistantMessage(sessionId.toString(), replacementText);

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
            riskEventMapper.insert(event);

            if (level != RiskLevel.YELLOW) {
                notificationService.notifyRiskEvent(event);
            }

            log.warn("Layer2 召回已执行: sessionId={}, decision={}, replaced={}, riskEventId={}",
                    sessionId, decision, replaced, event.getRiskEventId());
        } catch (Exception e) {
            log.error("Layer2 召回执行失败（不影响对话流）: sessionId={}, decision={}", sessionId, decision, e);
        }
    }

    /**
     * 替换会话记忆中最后一条 AI 回复。
     *
     * @return 是否实际替换（无 AI 回复时返回 false）
     */
    private boolean replaceLastAssistantMessage(String conversationId, String replacementText) {
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        int lastAssistantIdx = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof AssistantMessage) {
                lastAssistantIdx = i;
                break;
            }
        }
        if (lastAssistantIdx < 0) {
            log.warn("Layer2 召回：记忆中无 AI 回复可替换: conversationId={}", conversationId);
            return false;
        }
        List<Message> updated = new ArrayList<>(history);
        updated.set(lastAssistantIdx, new AssistantMessage(replacementText));
        chatMemoryRepository.saveAll(conversationId, updated);
        return true;
    }
}
