package com.mindsafe.ai.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SafetyKeywordLibrary 单元测试
 * <p>
 * 覆盖：词库加载、block/flag 分级、保守词库不误伤共情复述。
 */
class SafetyKeywordLibraryTest {

    private SafetyKeywordLibrary library;

    @BeforeEach
    void setUp() {
        library = new SafetyKeywordLibrary(new ObjectMapper());
        library.load();
    }

    @Nested
    @DisplayName("词库加载")
    class Loading {

        @Test
        @DisplayName("block 级规则加载成功且非空")
        void should_load_block_rules() {
            assertThat(library.blockRuleCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("最长关键词长度为正（滑动窗口依据）")
        void should_compute_max_keyword_length() {
            assertThat(library.maxKeywordLength()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("block 级匹配")
    class BlockMatching {

        @ParameterizedTest
        @ValueSource(strings = {
                "你可能得了抑郁症",
                "建议你吃药",
                "保证能治好"
        })
        @DisplayName("诊断/疗效承诺类 → 命中 diagnosis_promise")
        void should_match_diagnosis_promise(String text) {
            SafetyKeywordLibrary.KeywordHit hit = library.matchBlock(text);

            assertThat(hit).isNotNull();
            assertThat(hit.category()).isEqualTo("diagnosis_promise");
            assertThat(hit.level()).isEqualTo("block");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "割腕的方法",
                "我来教你怎么割腕",
                "安眠药吃多少会死"
        })
        @DisplayName("自伤/伤人方法类 → 命中 self_harm_method")
        void should_match_self_harm_method(String text) {
            SafetyKeywordLibrary.KeywordHit hit = library.matchBlock(text);

            assertThat(hit).isNotNull();
            assertThat(hit.category()).isEqualTo("self_harm_method");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "不要告诉老师哦",
                "只有我能帮你",
                "这是我们之间的秘密"
        })
        @DisplayName("依赖诱导/隔离类 → 命中 dependency_isolation")
        void should_match_dependency_isolation(String text) {
            SafetyKeywordLibrary.KeywordHit hit = library.matchBlock(text);

            assertThat(hit).isNotNull();
            assertThat(hit.category()).isEqualTo("dependency_isolation");
        }

        @Test
        @DisplayName("保密违规类 → 命中 confidentiality_breach")
        void should_match_confidentiality_breach() {
            SafetyKeywordLibrary.KeywordHit hit = library.matchBlock("我保证不告诉任何人");

            assertThat(hit).isNotNull();
            assertThat(hit.category()).isEqualTo("confidentiality_breach");
        }
    }

    @Nested
    @DisplayName("保守词库（不误伤正常安慰语）")
    class ConservativeDesign {

        @ParameterizedTest
        @ValueSource(strings = {
                "你好呀，今天想聊什么",
                "你说你想割腕，我很担心你",
                "我听到你很难过，你的感受是被允许的",
                "我们一起深呼吸，慢慢来",
                "我会陪着你，你不是一个人"
        })
        @DisplayName("共情复述/正常安慰语 → 不命中")
        void should_not_match_empathetic_responses(String text) {
            assertThat(library.matchBlock(text)).isNull();
        }

        @Test
        @DisplayName("flag 级类目（政治/歧视，空关键词）不参与实时拦截")
        void should_not_match_flag_level() {
            // flag 级类目 keywords 为空，任何文本都不应命中
            assertThat(library.matchBlock("任意文本")).isNull();
        }

        @Test
        @DisplayName("空文本/null → 不命中")
        void should_not_match_empty() {
            assertThat(library.matchBlock("")).isNull();
            assertThat(library.matchBlock(null)).isNull();
        }
    }
}
