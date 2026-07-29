package com.mindsafe.ai.orchestrator;

import java.util.List;

/**
 * 编排策略档案（ORCH-001，design/44 §4.3）
 * <p>
 * 由 {@link PromptOrchestrationService#resolve} 按"合规 > 情绪稳定 > 年龄 > 性格 > 画像 > 兴趣"
 * 优先级裁决计算得出，再渲染为 System Prompt 情绪策略层片段（EMO-001）。
 * <p>
 * 红线（design/44 §八）：本策略仅进 System 层，严禁向学生复述任何情绪/性格判断。
 */
public record StrategyProfile(
        int effectiveGrade,            // 含动态降级后的语言年级（design/29）
        EmotionState emotionState,     // STABLE / ACTIVATED / CRISIS
        String entryMood,              // 规范化后的情绪标签（design/44 §5.1 规范集）
        OpeningStrategy opening,       // 开场策略类别
        Pace pace,                     // 对话节奏
        SkillPriority skillPriority,   // 本轮技能优先级（PFA/SEL/CBT 排序）
        List<String> forbiddenActions, // 本状态禁止动作清单
        String emotionMirrorHint,      // 情绪镜映话术取材（按年龄+情绪，design/44 §5.3）
        boolean allowCbt,              // 情绪门控：是否允许进入认知工作（design/44 §5.4）
        boolean degraded,              // 是否触发语言降级（effectiveGrade < 真实年级）
        boolean safetyLocked           // 是否被合规裁决锁定（橙/红风险短路）
) {

    /** 情绪状态（容纳之窗，design/44 §5.4/§7.1） */
    public enum EmotionState {
        /** 窗口内：可正常推进（含按年龄 CBT） */
        STABLE("稳定"),
        /** 高唤醒/深度低落：情绪优先，压制 CBT 与追问 */
        ACTIVATED("情绪激活"),
        /** 危机态：交安全管线，编排让位 */
        CRISIS("危机");

        private final String label;

        EmotionState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 开场策略类别（design/44 §5.2 开场策略列的四类归纳） */
    public enum OpeningStrategy {
        NORMAL_ADVANCE("正常推进：轻松自然开场，可承接上次话题或一起放大积极体验"),
        HOLD_EMOTION("先接住情绪：共情命名感受，允许情绪存在，不急于给方案"),
        STABILIZE_FIRST("先稳定：命名情绪 + 接地/呼吸/安全感话术，再温和了解发生了什么"),
        LOW_PRESSURE_SPACE("留白低压：给选择题与留白，明确告诉孩子\"不想说也没关系\"");

        private final String label;

        OpeningStrategy(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 对话节奏 */
    public enum Pace {
        SLOW("slow：放慢节奏，短句，一次只说一件事，多留白等待"),
        NORMAL("normal：正常节奏推进");

        private final String label;

        Pace(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 本轮技能优先级（design/44 §5.2 优先技能列） */
    public enum SkillPriority {
        SEL_FIRST("SEL 巩固扩展 ＞ CBT"),
        POSITIVE_AMPLIFY("SEL 放大积极资源（不强行转向问题）"),
        PFA_GROUNDING("PFA 接地/呼吸稳定 ＞ CBT"),
        LISTEN_EMPATHY("倾听共情 ＞ CBT"),
        LISTEN_VENT("倾听宣泄（不评判） ＞ SEL"),
        PFA_SAFETY("PFA 安全感建立 ＞ CBT"),
        COMPANION_SPACE("低压陪伴 ＞ 留白"),
        CRISIS_HANDLING("危机稳定处置（交安全管线基调）");

        private final String label;

        SkillPriority(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
