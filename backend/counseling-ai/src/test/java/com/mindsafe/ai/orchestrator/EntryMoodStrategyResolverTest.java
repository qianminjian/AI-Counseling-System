package com.mindsafe.ai.orchestrator;

import com.mindsafe.ai.orchestrator.StrategyProfile.EmotionState;
import com.mindsafe.ai.orchestrator.StrategyProfile.OpeningStrategy;
import com.mindsafe.ai.orchestrator.StrategyProfile.Pace;
import com.mindsafe.ai.orchestrator.StrategyProfile.SkillPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EntryMoodStrategyResolver 单元测试（design/44 §5.1/§5.2/§5.3 + §十二单元层）
 */
class EntryMoodStrategyResolverTest {

    private final EntryMoodStrategyResolver resolver = new EntryMoodStrategyResolver();

    @Nested
    @DisplayName("normalize：情绪标签归一化（design/44 §5.1）")
    class Normalize {

        @ParameterizedTest
        @ValueSource(strings = {"calm", "happy", "anxious", "sad", "angry", "fearful", "withdrawn", "crisis"})
        void 规范集透传(String mood) {
            assertThat(resolver.normalize(mood)).isEqualTo(mood);
        }

        @Test
        void 学生端标签映射() {
            assertThat(resolver.normalize("scared")).isEqualTo("fearful");
            assertThat(resolver.normalize("nervous")).isEqualTo("anxious");
        }

        @Test
        void 缺失与未知回退calm() {
            assertThat(resolver.normalize(null)).isEqualTo("calm");
            assertThat(resolver.normalize("")).isEqualTo("calm");
            assertThat(resolver.normalize("  ")).isEqualTo("calm");
            assertThat(resolver.normalize("excited")).isEqualTo("calm");
        }
    }

    @Nested
    @DisplayName("resolve：8 情绪策略映射（design/44 §5.2）")
    class Resolve {

        @Test
        void calm_稳定态_允许CBT() {
            var s = resolver.resolve("calm");
            assertThat(s.emotionState()).isEqualTo(EmotionState.STABLE);
            assertThat(s.opening()).isEqualTo(OpeningStrategy.NORMAL_ADVANCE);
            assertThat(s.pace()).isEqualTo(Pace.NORMAL);
            assertThat(s.skillPriority()).isEqualTo(SkillPriority.SEL_FIRST);
            assertThat(s.allowCbt()).isTrue();
        }

        @Test
        void happy_稳定态_正向放大() {
            var s = resolver.resolve("happy");
            assertThat(s.emotionState()).isEqualTo(EmotionState.STABLE);
            assertThat(s.skillPriority()).isEqualTo(SkillPriority.POSITIVE_AMPLIFY);
            assertThat(s.allowCbt()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"anxious", "sad", "angry", "fearful", "withdrawn"})
        void 情绪激活态_一律门控禁CBT_慢节奏(String mood) {
            var s = resolver.resolve(mood);
            assertThat(s.emotionState()).isEqualTo(EmotionState.ACTIVATED);
            assertThat(s.pace()).isEqualTo(Pace.SLOW);
            assertThat(s.allowCbt()).isFalse();
        }

        @Test
        void anxious_先稳定_接地技能_禁认知重构() {
            var s = resolver.resolve("anxious");
            assertThat(s.opening()).isEqualTo(OpeningStrategy.STABILIZE_FIRST);
            assertThat(s.skillPriority()).isEqualTo(SkillPriority.PFA_GROUNDING);
            assertThat(s.forbiddenActions()).contains("不做认知重构");
        }

        @Test
        void withdrawn_低压力空间_不追问() {
            var s = resolver.resolve("withdrawn");
            assertThat(s.opening()).isEqualTo(OpeningStrategy.LOW_PRESSURE_SPACE);
            assertThat(s.skillPriority()).isEqualTo(SkillPriority.COMPANION_SPACE);
            assertThat(s.forbiddenActions()).contains("不追问");
        }

        @Test
        void crisis_危机态_危机处置_禁探索() {
            var s = resolver.resolve("crisis");
            assertThat(s.emotionState()).isEqualTo(EmotionState.CRISIS);
            assertThat(s.skillPriority()).isEqualTo(SkillPriority.CRISIS_HANDLING);
            assertThat(s.allowCbt()).isFalse();
            assertThat(s.forbiddenActions()).contains("禁止 CBT/认知重构");
        }
    }

    @Nested
    @DisplayName("mapVoiceEmotion：语音 SER 标签映射（VCL-001，design/47 §4.1）")
    class MapVoiceEmotion {

        @ParameterizedTest
        @CsvSource({
                "happy, happy",
                "neutral, calm",
                "sad, sad",
                "fearful, fearful",
                "angry, angry",
                "disgusted, angry"
        })
        void 六类标签映射规范集(String ser, String expected) {
            assertThat(resolver.mapVoiceEmotion(ser)).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"surprised", "other", "unknown", "", " "})
        void 不可用信号返回null回退entryMood(String ser) {
            assertThat(resolver.mapVoiceEmotion(ser)).isNull();
        }

        @Test
        void null输入返回null而非calm() {
            // 与 normalize 兜底 calm 区分：未知语音信号不能错当平静驱动策略
            assertThat(resolver.mapVoiceEmotion(null)).isNull();
        }
    }

    @Nested
    @DisplayName("mirrorHint：情绪镜映 × 年级段（design/44 §5.3）")
    class MirrorHint {

        @ParameterizedTest
        @CsvSource({
                "anxious, 1", "anxious, 3", "anxious, 5",
                "sad, 2", "sad, 4", "sad, 6",
                "angry, 1", "angry, 4", "angry, 6",
                "fearful, 2", "fearful, 3", "fearful, 5"
        })
        void 四情绪三年级段均有镜映话术(String mood, int grade) {
            assertThat(resolver.mirrorHint(mood, grade)).isNotBlank();
        }

        @Test
        void 低中高年级话术不同() {
            String low = resolver.mirrorHint("anxious", 1);
            String mid = resolver.mirrorHint("anxious", 3);
            String high = resolver.mirrorHint("anxious", 6);
            assertThat(low).isNotEqualTo(mid);
            assertThat(mid).isNotEqualTo(high);
        }

        @ParameterizedTest
        @ValueSource(strings = {"calm", "happy", "withdrawn", "crisis"})
        void 表外情绪不镜映(String mood) {
            assertThat(resolver.mirrorHint(mood, 3)).isEmpty();
        }
    }
}
