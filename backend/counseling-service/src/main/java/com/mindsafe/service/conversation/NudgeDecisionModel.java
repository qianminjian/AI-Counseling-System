package com.mindsafe.service.conversation;

import org.springframework.stereotype.Component;

/**
 * 冷场决策模型（design/28 §三 3.2）
 * <p>
 * 冷场发生时，由多信号加权计算出两个输出：
 * <ol>
 *   <li>暖场强度 warmthLevel：0=留白（不说话）/ 1=轻陪伴（安慰句、不提问）/ 2=引导破冰（轻松小问题）</li>
 *   <li>暖场方向 direction：决定这句话往哪个方向说（注入 TSK-004 Prompt）</li>
 * </ol>
 * <p>
 * 决策信号：A 情绪唤醒度 / B 沉默时长 / C 上一轮类型 / D 会话阶段 / E 风险（硬门槛）/ F 沟通偏好（学生画像）。
 * 硬规则优先于评分：风险≥橙色→留白；沉重倾诉宽限期内→留白（之后仅轻陪伴，绝不深挖）；
 * AI 刚提思考型问题且沉默未达思考时长→留白（design/14 §4.2 把思考时间还给孩子）。
 * <p>
 * 纯计算、无状态、无外部依赖，权重/阈值以常量集中，便于上线后调优与回归测试。
 * <p>
 * S-005（doing/93）：注册为 Bean 统一注入（原消费方内联 new，与同包决策组件实例化方式不一致）。
 */
@Component
public class NudgeDecisionModel {

    // ===== 可配置阈值（上线后按真实会话调优） =====

    /** 沉默时长分档下限（秒）：<25s 倾向留白 */
    static final int SILENCE_LIGHT_THRESHOLD = 25;
    /** 沉默时长分档上限（秒）：>45s 倾向引导破冰 */
    static final int SILENCE_DEEP_THRESHOLD = 45;
    /** 思考型问题的"思考时长"（秒）：AI 刚提思考型问题且沉默未达此值 → 留白 */
    static final int THINKING_GRACE_SECONDS = 45;
    /** 沉重倾诉宽限期（秒）：宽限期内 → 留白；之后仅轻陪伴 */
    static final int HEAVY_GRACE_SECONDS = 60;
    /** 画像信号 F：表达深度 ≥ 此值视为"话多"（偏留白） */
    static final double EXPRESSION_TALKATIVE = 0.6;
    /** 画像信号 F：表达深度 ≤ 此值视为"沉默性格"（偏暖场） */
    static final double EXPRESSION_RESERVED = 0.4;

    /**
     * 决策输入上下文（均可从 SessionState / 对话历史取得）
     *
     * @param emotionTag                    信号 A：会话情绪标签（happy/sad/angry/scared/nervous/neutral）
     * @param silenceSeconds                信号 B：前端上报的沉默时长（秒）
     * @param lastStudentMessageType        信号 C：最后一条学生消息类型（normal/perfunctory/heavy）
     * @param lastAiAskedThinkingQuestion   信号 C：AI 最后一句是否为思考型问题
     * @param turnCount                     信号 D：对话轮次
     * @param riskBlocked                   信号 E：融合风险≥橙色或 escalated（硬门槛）
     * @param secondsSinceLastStudentMessage 距孩子上次说话的秒数（沉重倾诉宽限期判断）
     * @param expressionDepth               信号 F：画像沟通偏好 expression_depth（nullable，首次对话为 null）
     */
    public record NudgeContext(
            String emotionTag,
            int silenceSeconds,
            String lastStudentMessageType,
            boolean lastAiAskedThinkingQuestion,
            int turnCount,
            boolean riskBlocked,
            long secondsSinceLastStudentMessage,
            Double expressionDepth
    ) {}

    /**
     * 决策输出
     *
     * @param warmthLevel 0=留白 / 1=轻陪伴 / 2=引导破冰
     * @param direction   暖场方向描述（warmthLevel=0 时为 null）
     */
    public record NudgeDecision(int warmthLevel, String direction) {
        /** 留白：把安静还给孩子 */
        public static final NudgeDecision SILENCE = new NudgeDecision(0, null);
    }

    /** 学生消息类型：敷衍回答（"嗯/哦/不知道"类短答） */
    public static final String MSG_PERFUNCTORY = "perfunctory";
    /** 学生消息类型：沉重倾诉（命中风险信号） */
    public static final String MSG_HEAVY = "heavy";
    /** 学生消息类型：轻微倾诉（负面情绪表达、未命中风险信号） */
    public static final String MSG_DISCLOSURE = "disclosure";
    /** 学生消息类型：普通 */
    public static final String MSG_NORMAL = "normal";

    /**
     * 执行决策：硬规则覆盖 → 加权评分 → 方向映射
     */
    public NudgeDecision decide(NudgeContext ctx) {
        // ===== 硬规则（优先于评分，不可覆盖） =====

        // E：风险 ≥ 橙色 或 escalated → 留白（安全流程接管，不做日常暖场）
        if (ctx.riskBlocked()) {
            return NudgeDecision.SILENCE;
        }

        // C：AI 刚提思考型问题且沉默未达"思考时长" → 留白（沉默是在思考，design/14 §4.2）
        if (ctx.lastAiAskedThinkingQuestion() && ctx.silenceSeconds() < THINKING_GRACE_SECONDS) {
            return NudgeDecision.SILENCE;
        }

        // C：孩子刚倾诉沉重内容（宽限期内）→ 留白
        boolean heavy = MSG_HEAVY.equals(ctx.lastStudentMessageType());
        if (heavy && ctx.secondsSinceLastStudentMessage() < HEAVY_GRACE_SECONDS) {
            return NudgeDecision.SILENCE;
        }

        // ===== 加权评分卡 =====
        int score = 0;
        int cap = 2; // warmthLevel 上限（恐惧/沉重倾诉场景压到轻陪伴）

        // B：沉默时长分档
        if (ctx.silenceSeconds() > SILENCE_DEEP_THRESHOLD) {
            score += 2;
        } else if (ctx.silenceSeconds() >= SILENCE_LIGHT_THRESHOLD) {
            score += 1;
        }

        // A：情绪唤醒度
        String emotion = ctx.emotionTag() == null ? "" : ctx.emotionTag();
        switch (emotion) {
            case "happy" -> score += 1;              // 开心 → 可早点轻松破冰
            case "sad", "nervous" -> score += 1;     // 低落/紧张 → 适时轻暖
            case "scared" -> cap = Math.min(cap, 1); // 恐惧 → 只轻陪伴不提问
            case "angry" -> score -= 1;              // 愤怒 → 先留白让其平复
            default -> {}                            // 中性
        }

        // C：上一轮类型
        String lastType = ctx.lastStudentMessageType() == null ? MSG_NORMAL : ctx.lastStudentMessageType();
        switch (lastType) {
            case MSG_PERFUNCTORY -> score += 1;      // 敷衍回答 → 降难度引导
            case MSG_DISCLOSURE -> {                 // 轻微倾诉 → 只轻陪伴不深挖
                score -= 1;
                cap = Math.min(cap, 1);
            }
            case MSG_HEAVY -> {                      // 沉重倾诉（宽限期后）→ 仅轻陪伴，绝不深挖
                score -= 2;
                cap = Math.min(cap, 1);
            }
            default -> {}
        }
        if (ctx.lastAiAskedThinkingQuestion()) {
            score -= 1;                              // 思考型问题（沉默已达思考时长）仍偏留白
            cap = Math.min(cap, 1);                  // 思考超时暖场：只轻问（降难度为选择题）
        }

        // D：会话阶段
        if (ctx.turnCount() <= 2) {
            score += 1;                              // 前期：轻暖建立关系
        } else if (ctx.turnCount() >= 10) {
            score -= 1;                              // 后期：倾向留白/温柔收束
        }

        // F：沟通偏好（学生画像）——话多偏留白，沉默性格偏暖场
        Double depth = ctx.expressionDepth();
        if (depth != null) {
            if (depth >= EXPRESSION_TALKATIVE) {
                score -= 1;                          // 话多：想说自然会说，沉默多在组织语言
            } else if (depth <= EXPRESSION_RESERVED) {
                score += 1;                          // 沉默性格：需更多主动引导
            }
        }

        // ===== 评分 → 强度 =====
        int warmthLevel = score <= 0 ? 0 : (score == 1 ? 1 : 2);
        warmthLevel = Math.min(warmthLevel, cap);
        if (warmthLevel == 0) {
            return NudgeDecision.SILENCE;
        }
        return new NudgeDecision(warmthLevel, resolveDirection(ctx, emotion, lastType));
    }

    /**
     * 暖场方向映射（design/28 §三 3.2 方向表）：由情绪 + 上一轮类型映射，注入 Prompt 引导 LLM
     */
    private String resolveDirection(NudgeContext ctx, String emotion, String lastType) {
        // 思考型问题 + 沉默已达思考时长 → 降难度（孩子答不出，换成选择题）
        if (ctx.lastAiAskedThinkingQuestion()) {
            return "降难度方向：孩子一时答不上刚才的问题，换成一个二选一选择题";
        }
        // 敷衍/"不知道" → 降难度方向优先（换二选一选择题，LANG-001 句式）
        if (MSG_PERFUNCTORY.equals(lastType)) {
            return "降难度方向：换成一个二选一选择题，让孩子轻松选一个答案";
        }
        // 沉重倾诉后 → 稳定化方向：肯定+陪伴，绝不深挖
        if (MSG_HEAVY.equals(lastType)) {
            return "稳定化方向：肯定孩子愿意说出来，温柔陪伴，绝不深挖刚才的倾诉";
        }
        return switch (emotion) {
            case "scared", "nervous" -> "安全感方向：传达'这里很安全，我陪着你'，不追问";
            case "sad" -> "共情陪伴方向：接住孩子的感受，温柔陪伴，不追问";
            case "angry" -> "降温/身体方向：建议深呼吸、喝口水等小动作帮助平复";
            case "happy" -> "话题延续方向：顺着刚才聊的高兴事，轻松延续话题";
            default -> "温柔陪伴方向：传达'不想说也没关系，我陪着你'";
        };
    }
}
