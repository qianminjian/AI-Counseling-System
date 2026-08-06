package com.mindsafe.service.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThemeEvolutionEngine 主题提取测试（ARCH-001 C1：话题关键词收敛单一源）。
 * <p>
 * findTopicHint 行为基线：从 ConversationServiceImpl.extractTopicHint 关键词表原样迁移
 * （内容 <4 字符直接 null、每轮最多 1 个、首个命中即返回），语义不调整只收敛位置。
 */
class ThemeEvolutionEngineTest {

    private ThemeEvolutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ThemeEvolutionEngine();
    }

    @Nested
    @DisplayName("单条消息话题提取 findTopicHint")
    class FindTopicHintTests {

        @Test
        @DisplayName("null 内容 → null")
        void findTopicHint_null() {
            assertThat(engine.findTopicHint(null)).isNull();
        }

        @Test
        @DisplayName("短内容（<4 字符）→ null")
        void findTopicHint_tooShort() {
            assertThat(engine.findTopicHint("同学")).isNull();
            assertThat(engine.findTopicHint("abc")).isNull();
        }

        @Test
        @DisplayName("同学 → 同学关系")
        void findTopicHint_classmate() {
            assertThat(engine.findTopicHint("我和同学一起做游戏")).isEqualTo("同学关系");
        }

        @Test
        @DisplayName("妈妈 → 和妈妈的关系")
        void findTopicHint_mother() {
            assertThat(engine.findTopicHint("我妈妈对我很好")).isEqualTo("和妈妈的关系");
        }

        @Test
        @DisplayName("考试 → 考试压力")
        void findTopicHint_exam() {
            assertThat(engine.findTopicHint("下周要考试我好紧张")).isEqualTo("考试压力");
        }

        @Test
        @DisplayName("不想活 → 自伤倾向")
        void findTopicHint_selfHarm() {
            assertThat(engine.findTopicHint("我不想活了怎么办")).isEqualTo("自伤倾向");
        }

        @Test
        @DisplayName("孤独 → 孤独感")
        void findTopicHint_lonely() {
            assertThat(engine.findTopicHint("我觉得很孤独没人理我")).isEqualTo("孤独感");
        }

        @Test
        @DisplayName("多关键词命中 → 返回表序首个（每轮最多 1 个）")
        void findTopicHint_firstMatch() {
            // "同学"在表中先于"妈妈"，即使句子同时含两者也只返回"同学关系"
            assertThat(engine.findTopicHint("我和同学吵架了妈妈也骂我")).isEqualTo("同学关系");
        }

        @Test
        @DisplayName("无关键词 → null")
        void findTopicHint_noMatch() {
            assertThat(engine.findTopicHint("今天天气很好适合出去玩")).isNull();
        }
    }
}
