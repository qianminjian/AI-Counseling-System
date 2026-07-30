package com.mindsafe.service.offline;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线消息队列与重放幂等（TOOL-003，design/36 M3）
 * <p>
 * <ul>
 *   <li>幂等去重：服务端按 (userId, clientMsgId) 唯一约束</li>
 *   <li>批量重放合并：≥3 条时 AI 收到合并上下文回一条</li>
 *   <li>安全约束：重放消息同样过风险检测，标注离线时间</li>
 *   <li>超时丢弃：>24h 的排队消息标记"未送达"</li>
 * </ul>
 * 纯函数实现。接线时由 chat 消息接口 + 重放流程消费。
 */
@Component
public class OfflineMessageReplayService {

    /** 批量重放合并阈值：≥ 此数量时合并为一条 AI 回复 */
    public static final int BATCH_MERGE_THRESHOLD = 3;

    /** 消息最大排队时长（小时）：超过则标记未送达 */
    public static final int MAX_QUEUE_HOURS = 24;

    /** 退避序列（秒） */
    public static final int[] BACKOFF_SECONDS = {1, 5, 30};

    // ==================== 幂等去重 ====================

    /** 去重结果 */
    public record DeduplicationResult(
            boolean accepted,
            String clientMsgId,
            String reason
    ) {
    }

    /**
     * 幂等去重检查。
     * 服务端按 (userId, clientMsgId) 唯一索引，重复则拒绝。
     *
     * @param clientMsgId  客户端消息 ID
     * @param existingIds  已存在的 clientMsgId 集合
     * @return 去重结果
     */
    public DeduplicationResult deduplicate(String clientMsgId, Set<String> existingIds) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return new DeduplicationResult(true, clientMsgId, "无 clientMsgId，按普通消息处理");
        }
        if (existingIds != null && existingIds.contains(clientMsgId)) {
            return new DeduplicationResult(false, clientMsgId, "重复消息（幂等拒绝）");
        }
        return new DeduplicationResult(true, clientMsgId, "新消息，接受");
    }

    // ==================== 批量重放合并 ====================

    /** 排队消息 */
    public record QueuedMessage(
            String clientMsgId,
            String content,
            Instant queuedAt,
            boolean containsRisk
    ) {
    }

    /** 重放策略 */
    public record ReplayStrategy(
            boolean mergeReply,
            int messageCount,
            List<QueuedMessage> validMessages,
            List<QueuedMessage> expiredMessages,
            List<QueuedMessage> riskMessages,
            String aiContext
    ) {
    }

    /**
     * 计算重放策略。
     * 规则：
     * - >24h 的消息标记过期
     * - ≥3 条有效消息时合并为一条 AI 回复
     * - 含风险内容的消息标注离线时间
     *
     * @param messages 排队消息列表（按时间序）
     * @param now      当前时间
     * @return 重放策略
     */
    public ReplayStrategy computeReplayStrategy(List<QueuedMessage> messages, Instant now) {
        if (messages == null || messages.isEmpty()) {
            return new ReplayStrategy(false, 0, List.of(), List.of(), List.of(), "");
        }

        List<QueuedMessage> valid = new ArrayList<>();
        List<QueuedMessage> expired = new ArrayList<>();
        List<QueuedMessage> risk = new ArrayList<>();

        for (QueuedMessage msg : messages) {
            long hoursQueued = ChronoUnit.HOURS.between(msg.queuedAt(), now);
            if (hoursQueued > MAX_QUEUE_HOURS) {
                expired.add(msg);
            } else {
                valid.add(msg);
                if (msg.containsRisk()) {
                    risk.add(msg);
                }
            }
        }

        boolean merge = valid.size() >= BATCH_MERGE_THRESHOLD;

        // 合并上下文
        String aiContext;
        if (merge) {
            StringBuilder sb = new StringBuilder();
            sb.append("[离线期间学生发送了 ").append(valid.size()).append(" 条消息，合并如下]\n");
            for (QueuedMessage msg : valid) {
                sb.append("- ").append(msg.content()).append("\n");
            }
            aiContext = sb.toString();
        } else {
            aiContext = valid.isEmpty() ? "" : valid.get(valid.size() - 1).content();
        }

        return new ReplayStrategy(merge, valid.size(), valid, expired, risk, aiContext);
    }

    // ==================== 退避策略 ====================

    /**
     * 计算连续失败后的退避时间。
     *
     * @param consecutiveFailures 连续失败次数
     * @return 退避秒数
     */
    public int getBackoffSeconds(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0;
        int idx = Math.min(consecutiveFailures - 1, BACKOFF_SECONDS.length - 1);
        return BACKOFF_SECONDS[idx];
    }

    /**
     * 判断是否应放弃重放（超过最大退避后仍失败）。
     *
     * @param consecutiveFailures 连续失败次数
     * @return true=放弃，标记未送达
     */
    public boolean shouldAbandon(int consecutiveFailures) {
        return consecutiveFailures > BACKOFF_SECONDS.length;
    }

    // ==================== 风险标注 ====================

    /**
     * 为重放消息中的风险内容生成标注。
     *
     * @param riskMessages 含风险的消息
     * @return 标注文本（附加到预警中）
     */
    public String buildRiskAnnotation(List<QueuedMessage> riskMessages) {
        if (riskMessages == null || riskMessages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("⚠️ 以下消息产生于离线期间：\n");
        for (QueuedMessage msg : riskMessages) {
            sb.append("- [").append(msg.queuedAt()).append("] ")
                    .append(msg.content(), 0, Math.min(msg.content().length(), 50))
                    .append("…\n");
        }
        return sb.toString();
    }
}
