package com.mindsafe.ai.orchestrator;

import com.mindsafe.ai.orchestrator.StrategyProfile.EmotionState;
import com.mindsafe.ai.orchestrator.StrategyProfile.OpeningStrategy;
import com.mindsafe.ai.orchestrator.StrategyProfile.Pace;
import com.mindsafe.ai.orchestrator.StrategyProfile.SkillPriority;
import com.mindsafe.common.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptOrchestrationService 单元测试（ORCH-001/002，design/44 §4.2/§4.4/§十二）
 * <p>
 * 覆盖：合规裁决短路、情绪门控 allowCbt、轮级情绪优先、年级降级透传、模板变量契约、
 * 画像微调置信门控（PROF-022，design/46 §5.1/§5.2）。
 */
class PromptOrchestrationServiceTest {

    private final PromptOrchestrationService service =
            new PromptOrchestrationService(new EntryMoodStrategyResolver());

    private OrchestrationContext ctx(int grade, int effectiveGrade, String entryMood,
                                     String currentEmotion, RiskLevel riskLevel) {
        return new OrchestrationContext(grade, effectiveGrade, entryMood, currentEmotion, riskLevel, null);
    }

    @Nested
    @DisplayName("合规裁决（design/44 §4.4 铁律最高位）")
    class SafetyOverride {

        @Test
        void 橙色风险_安全锁定_危机策略_禁CBT() {
            StrategyProfile p = service.resolve(ctx(5, 3, "happy", null, RiskLevel.ORANGE));
            assertThat(p.safetyLocked()).isTrue();
            assertThat(p.emotionState()).isEqualTo(EmotionState.CRISIS);
            assertThat(p.skillPriority()).isEqualTo(SkillPriority.CRISIS_HANDLING);
            assertThat(p.allowCbt()).isFalse();
            assertThat(p.forbiddenActions()).contains("禁止 CBT/认知重构");
        }

        @Test
        void 安全话术不降级_用真实年级() {
            // design/29 §3.11：安全场景话术不做年级降级
            StrategyProfile p = service.resolve(ctx(5, 3, "sad", null, RiskLevel.RED));
            assertThat(p.effectiveGrade()).isEqualTo(5);
            assertThat(p.degraded()).isFalse();
        }

        @Test
        void 黄色风险_不触发安全锁定_走正常情绪策略() {
            StrategyProfile p = service.resolve(ctx(3, 3, "sad", null, RiskLevel.YELLOW));
            assertThat(p.safetyLocked()).isFalse();
            assertThat(p.emotionState()).isEqualTo(EmotionState.ACTIVATED);
        }

        @Test
        void 无风险信号_null不炸() {
            StrategyProfile p = service.resolve(ctx(3, 3, "calm", null, null));
            assertThat(p.safetyLocked()).isFalse();
            assertThat(p.allowCbt()).isTrue();
        }
    }

    @Nested
    @DisplayName("情绪门控 allowCbt（design/44 §5.4）")
    class EmotionGate {

        @Test
        void 情绪激活态_门控关闭CBT() {
            StrategyProfile p = service.resolve(ctx(4, 4, "anxious", null, RiskLevel.GREEN));
            assertThat(p.emotionState()).isEqualTo(EmotionState.ACTIVATED);
            assertThat(p.allowCbt()).isFalse();
            assertThat(p.opening()).isEqualTo(OpeningStrategy.STABILIZE_FIRST);
        }

        @Test
        void 稳定态_放行CBT() {
            StrategyProfile p = service.resolve(ctx(4, 4, "happy", null, RiskLevel.GREEN));
            assertThat(p.emotionState()).isEqualTo(EmotionState.STABLE);
            assertThat(p.allowCbt()).isTrue();
        }

        @Test
        void 学生端标签scared_归一为fearful策略() {
            StrategyProfile p = service.resolve(ctx(2, 2, "scared", null, null));
            assertThat(p.entryMood()).isEqualTo("fearful");
            assertThat(p.skillPriority()).isEqualTo(SkillPriority.PFA_SAFETY);
            assertThat(p.allowCbt()).isFalse();
        }
    }

    @Nested
    @DisplayName("情绪来源与年级透传")
    class EmotionSourceAndGrade {

        @Test
        void 轮级currentEmotion优先于会话级entryMood() {
            // VCL-001 语音情绪接入后：会话开始 happy，本轮语音检出 sad → 按 sad 编排
            StrategyProfile p = service.resolve(ctx(4, 4, "happy", "sad", null));
            assertThat(p.entryMood()).isEqualTo("sad");
            assertThat(p.emotionState()).isEqualTo(EmotionState.ACTIVATED);
            assertThat(p.allowCbt()).isFalse();
        }

        @Test
        void currentEmotion空白_回退entryMood() {
            StrategyProfile p = service.resolve(ctx(4, 4, "angry", "  ", null));
            assertThat(p.entryMood()).isEqualTo("angry");
        }

        @Test
        void 年级降级透传_镜映按有效年级取材() {
            StrategyProfile p = service.resolve(ctx(5, 2, "anxious", null, null));
            assertThat(p.degraded()).isTrue();
            assertThat(p.effectiveGrade()).isEqualTo(2);
            // 低年级段镜映话术（design/44 §5.3）
            assertThat(p.emotionMirrorHint()).contains("小鹿");
        }
    }

    @Nested
    @DisplayName("toTemplateVariables：EMO-001 变量契约（design/45 §4.3）")
    class TemplateVariables {

        @Test
        void 八个变量齐全且非空() {
            StrategyProfile p = service.resolve(ctx(3, 3, "sad", null, null));
            Map<String, String> vars = service.toTemplateVariables(p);
            assertThat(vars).containsOnlyKeys(
                    "emotion_state", "entry_mood", "opening", "pace",
                    "mirror_hint", "skill_priority", "forbidden_actions", "cbt_gate");
            assertThat(vars.values()).allSatisfy(v -> assertThat(v).isNotBlank());
        }

        @Test
        void 门控关闭_cbt_gate为不进入认知重构() {
            StrategyProfile p = service.resolve(ctx(3, 3, "anxious", null, null));
            assertThat(service.toTemplateVariables(p).get("cbt_gate")).contains("不进入认知重构");
        }

        @Test
        void 门控放行_cbt_gate为可温和推进() {
            StrategyProfile p = service.resolve(ctx(3, 3, "calm", null, null));
            assertThat(service.toTemplateVariables(p).get("cbt_gate")).contains("温和推进");
        }

        @Test
        void 无镜映情绪_提示自然回应() {
            StrategyProfile p = service.resolve(ctx(3, 3, "calm", null, null));
            assertThat(service.toTemplateVariables(p).get("mirror_hint")).contains("自然回应");
        }

        @Test
        void 无禁忌_显示无特殊禁止() {
            StrategyProfile p = service.resolve(ctx(3, 3, "calm", null, null));
            assertThat(service.toTemplateVariables(p).get("forbidden_actions")).isEqualTo("无特殊禁止");
        }
    }

    @Nested
    @DisplayName("画像微调置信门控（PROF-022，design/46 §5.1/§5.2）")
    class ProfileTuning {

        private OrchestrationContext ctxWithSignals(String entryMood, RiskLevel riskLevel, ProfileSignals signals) {
            return new OrchestrationContext(4, 4, entryMood, null, riskLevel, signals);
        }

        @Test
        void 高内向高置信_稳定态_改留白低压开场放慢节奏() {
            ProfileSignals signals = new ProfileSignals(0.8, 0.6, List.of(), 0.0);
            StrategyProfile p = service.resolve(ctxWithSignals("happy", null, signals));
            assertThat(p.opening()).isEqualTo(OpeningStrategy.LOW_PRESSURE_SPACE);
            assertThat(p.pace()).isEqualTo(Pace.SLOW);
            // 微调不碰情绪门控：稳定态 CBT 仍放行
            assertThat(p.allowCbt()).isTrue();
        }

        @Test
        void 高内向低置信_不参与编排() {
            // design/46 §5.2：宁可不用，不可乱用
            ProfileSignals signals = new ProfileSignals(0.8, 0.33, List.of(), 0.0);
            StrategyProfile p = service.resolve(ctxWithSignals("happy", null, signals));
            assertThat(p.opening()).isEqualTo(OpeningStrategy.NORMAL_ADVANCE);
            assertThat(p.pace()).isEqualTo(Pace.NORMAL);
        }

        @Test
        void 激活态_情绪策略优先_内向不覆盖开场() {
            // 优先级铁律：情绪 > 画像——anxious 的 STABILIZE_FIRST 不被内向微调改写
            ProfileSignals signals = new ProfileSignals(0.9, 0.9, List.of(), 0.0);
            StrategyProfile p = service.resolve(ctxWithSignals("anxious", null, signals));
            assertThat(p.opening()).isEqualTo(OpeningStrategy.STABILIZE_FIRST);
        }

        @Test
        void 兴趣可用_镜映取材拼入提示() {
            ProfileSignals signals = new ProfileSignals(null, 0.0, List.of("恐龙", "画画"), 0.6);
            StrategyProfile p = service.resolve(ctxWithSignals("sad", null, signals));
            assertThat(p.emotionMirrorHint()).contains("恐龙", "画画");
            // 情绪镜映原句仍在（取材是追加不是替换）
            assertThat(p.emotionMirrorHint().length()).isGreaterThan("孩子喜欢".length());
        }

        @Test
        void 兴趣低置信_不拼入取材() {
            ProfileSignals signals = new ProfileSignals(null, 0.0, List.of("恐龙"), 0.33);
            StrategyProfile p = service.resolve(ctxWithSignals("calm", null, signals));
            assertThat(p.emotionMirrorHint()).doesNotContain("恐龙");
        }

        @Test
        void 合规裁决短路_画像微调全部失效() {
            // 优先级铁律最高位：橙色风险锁定后一切个性化短路
            ProfileSignals signals = new ProfileSignals(0.9, 0.9, List.of("恐龙"), 0.9);
            StrategyProfile p = service.resolve(ctxWithSignals("happy", RiskLevel.ORANGE, signals));
            assertThat(p.safetyLocked()).isTrue();
            assertThat(p.opening()).isEqualTo(OpeningStrategy.STABILIZE_FIRST);
            assertThat(p.emotionMirrorHint()).doesNotContain("恐龙");
        }
    }
}
