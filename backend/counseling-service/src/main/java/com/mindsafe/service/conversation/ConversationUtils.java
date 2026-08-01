package com.mindsafe.service.conversation;

import com.mindsafe.ai.safety.CrisisResources;

import java.util.Set;

/**
 * 对话领域纯函数工具集（无状态、无 Spring 依赖）。
 * <p>
 * 从 ConversationServiceImpl 提取，保持 public static 签名不变。
 */
public final class ConversationUtils {

    private ConversationUtils() {}

    /**
     * 解析 gradeCode 为年级数字（1-6）。
     * <p>
     * 支持格式："G1"~"G6"、"1"~"6"、null/空/无法解析 → 默认 4（中间值，design/29 §3.3）
     * <p>
     * public：供 VoicePersonaResolver（TMATCH-001，voice 包）复用同一年级解析口径。
     */
    public static int parseGradeCode(String gradeCode) {
        if (gradeCode == null || gradeCode.isBlank()) return 4;
        String cleaned = gradeCode.trim().toUpperCase();
        // 去掉 "G" 前缀（如 "G3" → "3"）
        if (cleaned.startsWith("G")) {
            cleaned = cleaned.substring(1);
        }
        try {
            int grade = Integer.parseInt(cleaned);
            return (grade >= 1 && grade <= 6) ? grade : 4;
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /**
     * PROF-015：动态降级机制——根据表达深度调整语言复杂度。
     * <p>
     * 规则（design/29 §3.11）：
     * <ul>
     *   <li>expressionDepth < 0.15（极端沉默）→ 直接使用 1-2 年级模板（effectiveGrade=1）</li>
     *   <li>expressionDepth < 0.3 且 grade > 2 → 降 2 个年级（如 5→3）</li>
     *   <li>风险场景（橙/红）→ 不降级（安全话术需要认知匹配）</li>
     * </ul>
     *
     * @param grade           实际年级（1-6）
     * @param expressionDepth 画像表达深度（null 表示无数据，不降级）
     * @param riskBlocked     是否处于风险场景（橙/红）
     * @return 有效年级（用于选择语言模板）
     */
    public static int computeEffectiveGrade(int grade, Double expressionDepth, boolean riskBlocked) {
        if (riskBlocked || expressionDepth == null) {
            return grade;
        }
        if (expressionDepth < 0.15) {
            return 1; // 极端沉默 → 直接用最简单语言
        }
        if (expressionDepth < 0.3 && grade > 2) {
            return Math.max(1, grade - 2);
        }
        return grade;
    }

    /**
     * RISK-201：RED 短路安全文案选择（分年级两版，预审核模板，不由 LLM 生成）
     * <p>
     * 1-2 年级 → 短句版；3-6 年级 → 标准版（含热线，design/04 §18.2）。
     */
    public static String redSafetyReply(int grade) {
        return grade <= 2
                ? CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE
                : CrisisResources.RED_SAFETY_REPLY;
    }

    /**
     * 构建问候语：个性化"哈喽，[昵称]！" + 情绪问候（design/28 §2.2）
     * <p>
     * 唤醒词 onboarding：用"哈喽+名字"模式自然引导孩子回应"哈喽波波"；
     * 始终生效（不依赖语音唤醒模式）；昵称缺失时回退通用问候。
     */
    public static String buildGreeting(String emotionTag, String pseudonym) {
        // 占位符昵称不当真名用（如“某人”/“同学”/“小朋友”）
        boolean validName = pseudonym != null && !pseudonym.isBlank()
                && !java.util.Set.of("某人", "同学", "小朋友", "学生", "user", "test", "测试", "匿名", "unknown")
                        .contains(pseudonym.trim().toLowerCase());
        String hello = validName
                ? "哈喽，" + pseudonym + "！"
                : "哈喽！";
        String emotionGreeting = switch (emotionTag) {
            case "happy" -> "看起来你今天心情不错呀！想和我聊聊什么开心的事吗？😊";
            case "sad" -> "我感觉到你今天有点难过。没关系，我在这里陪着你，想和我说说吗？💙";
            case "angry" -> "看起来你现在有些生气。生气是很正常的感受哦，想和我聊聊发生了什么吗？";
            case "scared" -> "我感觉到你有些害怕。别担心，这里很安全，我会一直陪着你。🌟";
            case "nervous" -> "看起来你有点紧张。深呼吸一下，我们慢慢聊，不着急。🌈";
            default -> "我是波波，今天想和我聊些什么呢？";
        };
        return hello + emotionGreeting;
    }

    // ===== 冷场决策模型辅助（design/28 §三 3.2 信号 C） =====

    /** 敷衍回答词集（"嗯/哦/不知道"类短答） */
    private static final Set<String> PERFUNCTORY_REPLIES = Set.of(
            "嗯", "哦", "喔", "好", "好的", "是", "是的", "啊", "行", "可以",
            "不知道", "不晓得", "随便", "还行", "还好", "嗯嗯", "哦哦", "没有", "没", "不想说");

    /** 负面情绪标签集（用于轻微倾诉判定：表达了感受但未命中风险信号） */
    private static final Set<String> DISTRESS_EMOTIONS = Set.of(
            "sad", "angry", "scared", "nervous");

    /**
     * 分类学生消息类型（信号 C）：沉重倾诉（命中风险信号）/ 敷衍回答 / 轻微倾诉 / 普通
     * <p>
     * 轻微倾诉：负面情绪 + 有一定内容长度（表达了感受，但未命中风险信号）→ 决策模型只轻陪伴不深挖。
     */
    public static String classifyStudentMessage(String content, boolean risky, String emotionTag) {
        if (risky) {
            return NudgeDecisionModel.MSG_HEAVY;
        }
        String stripped = content == null ? "" : content.replaceAll("[\\s，。！？!?~～…·、\"'\u201c\u201d（）()]", "");
        if (!stripped.isEmpty() && stripped.length() <= 5 && PERFUNCTORY_REPLIES.contains(stripped)) {
            return NudgeDecisionModel.MSG_PERFUNCTORY;
        }
        // 轻微倾诉：负面情绪 + 有内容（如"没人和我玩"），未命中风险信号
        if (stripped.length() > 5 && emotionTag != null && DISTRESS_EMOTIONS.contains(emotionTag)) {
            return NudgeDecisionModel.MSG_DISCLOSURE;
        }
        return NudgeDecisionModel.MSG_NORMAL;
    }
}
