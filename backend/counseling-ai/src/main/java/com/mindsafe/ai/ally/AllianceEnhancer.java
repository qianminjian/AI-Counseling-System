package com.mindsafe.ai.ally;

import org.springframework.stereotype.Component;

/**
 * 治疗联盟增强器（ALLY-201/202/203，design/52 §五）
 * <p>
 * ALLY-201：连续性开场（记忆回注生成续接话术）
 * ALLY-202：收束"巩固-希望-桥接"结构化
 * ALLY-203：中断-回归照护信号
 * <p>
 * 纯模板/规则实现，不依赖 LLM。话术模板由 Prompt 层消费。
 */
@Component
public class AllianceEnhancer {

    /**
     * ALLY-201：生成连续性开场提示（注入 System Prompt）。
     * <p>
     * 利用已实现的记忆回注（LongTermMemoryService），生成"上次我们聊到…，今天怎么样"式续接。
     *
     * @param lastTopicSummary 上次会话主题摘要（来自记忆回注，可为 null）
     * @param pseudonym        学生化名
     * @return 连续性开场提示（注入 System），null=无历史不生成
     */
    public String buildContinuityPrompt(String lastTopicSummary, String pseudonym) {
        if (lastTopicSummary == null || lastTopicSummary.isBlank()) {
            return null; // 首次对话或无记忆 → 不生成续接
        }
        return String.format(
                "【连续性开场】上次你和%s聊到了「%s」相关的事情。" +
                "今天开始时，可以用自然的方式续接（如'上次你提到...，后来怎么样了？'），" +
                "但不要生硬复述，也不要强迫学生继续上次话题——尊重学生今天想聊的新话题。",
                pseudonym, lastTopicSummary);
    }

    /**
     * ALLY-202：生成收束结构化提示（巩固-希望-桥接三段式）。
     *
     * @param sessionHighlight 本次会话亮点/进步（来自 AI 总结，可为 null）
     * @param nextToolHint     下次可尝试的工具/技能提示（可为 null）
     * @return 收束提示（注入 System）
     */
    public String buildClosurePrompt(String sessionHighlight, String nextToolHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("【收束结构化】会话即将结束，请按三段式收束：\n");
        sb.append("1. 巩固：肯定学生今天的参与和表达");
        if (sessionHighlight != null && !sessionHighlight.isBlank()) {
            sb.append("（特别提到：").append(sessionHighlight).append("）");
        }
        sb.append("\n");
        sb.append("2. 希望：传递'事情可以慢慢变好'的信心，不说空话\n");
        sb.append("3. 桥接：预告下次可以再聊/可以试的小行动");
        if (nextToolHint != null && !nextToolHint.isBlank()) {
            sb.append("（如：下次可以试试").append(nextToolHint).append("）");
        }
        sb.append("\n");
        sb.append("注意：收束要简短温暖（≤3 句），不要说教，不要布置'作业'。");
        return sb.toString();
    }

    /**
     * ALLY-203：生成中断-回归照护信号。
     * <p>
     * 当学生超过 N 天未对话后回归，生成照护性开场（非质问"你怎么这么久没来"）。
     *
     * @param daysAbsent 缺席天数
     * @param pseudonym  学生化名
     * @return 回归照护提示（注入 System），null=无需特殊照护
     */
    public String buildReturnCarePrompt(int daysAbsent, String pseudonym) {
        if (daysAbsent < 7) return null; // 一周内无需特殊照护

        String tone;
        if (daysAbsent >= 30) {
            tone = "温暖欢迎，不追问原因，让学生感到'随时可以回来，这里一直等你'";
        } else if (daysAbsent >= 14) {
            tone = "轻松问候，可以提一句'好久不见'，但不追问为什么没来";
        } else {
            tone = "自然续接，像朋友一样'嗨，今天想聊点什么？'";
        }

        return String.format(
                "【回归照护】%s已经 %d 天没来对话了。今天的开场要%s。" +
                "绝对不要说'你怎么这么久没来''你是不是不想聊了'这类让学生有压力的话。" +
                "如果学生主动解释原因，倾听即可，不评判。",
                pseudonym, daysAbsent, tone);
    }

    /**
     * 判断是否需要回归照护信号。
     */
    public boolean needsReturnCare(int daysAbsent) {
        return daysAbsent >= 7;
    }
}
