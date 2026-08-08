package com.mindsafe.service.toolbox;

import com.mindsafe.service.toolbox.MoodCheckRecorder.EffectLevel;
import com.mindsafe.service.toolbox.MoodCheckRecorder.MoodEffect;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolCategory;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOOL-001/002 心理工具箱 + SOS 单元测试
 */
class ToolboxTest {

    private final ToolboxRegistry registry = new ToolboxRegistry();
    private final MoodCheckRecorder recorder = new MoodCheckRecorder();

    @Nested
    @DisplayName("ToolboxRegistry")
    class RegistryTests {

        @Test
        @DisplayName("内置 5 个工具")
        void fiveTools() {
            assertThat(registry.listAll()).hasSize(5);
        }

        @Test
        @DisplayName("按 ID 获取")
        void getById() {
            assertThat(registry.getById("grounding_54321")).isPresent();
            assertThat(registry.getById("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("年级过滤：1 年级不含正念/安全岛（minGrade=2）")
        void gradeFilter() {
            List<ToolDefinition> grade1 = registry.listForGrade(1);
            assertThat(grade1).hasSize(3); // breathing, grounding, thermometer
            assertThat(grade1).noneMatch(t -> t.toolId().equals("mindful_frog"));
            assertThat(grade1).noneMatch(t -> t.toolId().equals("safe_island"));

            List<ToolDefinition> grade2 = registry.listForGrade(2);
            assertThat(grade2).hasSize(5);
        }

        @Test
        @DisplayName("SOS 工具：仅接地+呼吸")
        void sosTools() {
            List<ToolDefinition> sos = registry.listSosTools();
            assertThat(sos).hasSize(2);
            assertThat(sos).allMatch(t ->
                    t.category() == ToolCategory.GROUNDING || t.category() == ToolCategory.BREATHING);
        }

        @Test
        @DisplayName("按分类获取")
        void byCategory() {
            assertThat(registry.listByCategory(ToolCategory.GROUNDING)).hasSize(1);
            assertThat(registry.listByCategory(ToolCategory.MINDFULNESS)).hasSize(1);
        }

        @Test
        @DisplayName("工具配置完整性")
        void toolConfig() {
            ToolDefinition grounding = registry.getById("grounding_54321").orElseThrow();
            assertThat(grounding.title()).isEqualTo("找一找");
            assertThat(grounding.durationSec()).isEqualTo(180);
            assertThat(grounding.preMoodCheck()).isTrue();
            assertThat(grounding.postMoodCheck()).isTrue();
            // BA-03：接地练习无 relaxation 数据源（mood-check 不落库），不声明徽章
            assertThat(grounding.rewardBadge()).isNull();
            assertThat(registry.getById("breathing_box").orElseThrow().rewardBadge()).isEqualTo("breathing_star");
        }
    }

    @Nested
    @DisplayName("MoodCheckRecorder")
    class MoodTests {

        @Test
        @DisplayName("改善：pre=2, post=4 → delta=2, IMPROVED")
        void improved() {
            MoodEffect effect = recorder.record("breathing_box", 2, 4);
            assertThat(effect.delta()).isEqualTo(2);
            assertThat(effect.level()).isEqualTo(EffectLevel.IMPROVED);
            assertThat(recorder.needsAttention(effect)).isFalse();
        }

        @Test
        @DisplayName("无变化：pre=3, post=3 → UNCHANGED")
        void unchanged() {
            MoodEffect effect = recorder.record("grounding_54321", 3, 3);
            assertThat(effect.delta()).isZero();
            assertThat(effect.level()).isEqualTo(EffectLevel.UNCHANGED);
        }

        @Test
        @DisplayName("恶化：pre=4, post=2 → WORSENED, needsAttention")
        void worsened() {
            MoodEffect effect = recorder.record("mindful_frog", 4, 2);
            assertThat(effect.delta()).isEqualTo(-2);
            assertThat(effect.level()).isEqualTo(EffectLevel.WORSENED);
            assertThat(recorder.needsAttention(effect)).isTrue();
        }

        @Test
        @DisplayName("无效数据：超出 1-5 范围 → INVALID")
        void invalid() {
            assertThat(recorder.record("t", 0, 3).level()).isEqualTo(EffectLevel.INVALID);
            assertThat(recorder.record("t", 3, 6).level()).isEqualTo(EffectLevel.INVALID);
            assertThat(recorder.record("t", -1, 3).level()).isEqualTo(EffectLevel.INVALID);
        }

        @Test
        @DisplayName("边界：1 和 5 合法")
        void boundary() {
            assertThat(recorder.isValidMood(1)).isTrue();
            assertThat(recorder.isValidMood(5)).isTrue();
            assertThat(recorder.isValidMood(0)).isFalse();
            assertThat(recorder.isValidMood(6)).isFalse();
        }
    }
}
