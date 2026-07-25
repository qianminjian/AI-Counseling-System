package com.mindsafe.ai.safety;

import com.mindsafe.common.dto.chat.StreamMessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OutputContentFilter（Layer1 流式实时过滤）单元测试
 * <p>
 * 覆盖：正常流零干扰、关键词跨 token 命中、block 后中断并输出安全模板、
 * 自伤类追加危机热线、上报失败不影响流。
 */
class OutputContentFilterTest {

    private SafetyKeywordLibrary library;
    private OutputSafetyReporter reporter;
    private OutputContentFilter filter;

    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        library = new SafetyKeywordLibrary();
        library.load();
        reporter = mock(OutputSafetyReporter.class);
        filter = new OutputContentFilter(library, reporter);
    }

    @Nested
    @DisplayName("正常流零干扰")
    class NormalFlow {

        @Test
        @DisplayName("正常 token 流原样透传（无漏字、顺序正确）")
        void should_pass_through_normal_tokens() {
            StepVerifier.create(filter.apply(Flux.just("你", "好", "呀", "，", "想聊什么"), sessionId))
                    .expectNextMatches(evt -> isToken(evt, "你"))
                    .expectNextMatches(evt -> isToken(evt, "好"))
                    .expectNextMatches(evt -> isToken(evt, "呀"))
                    .expectNextMatches(evt -> isToken(evt, "，"))
                    .expectNextMatches(evt -> isToken(evt, "想聊什么"))
                    .verifyComplete();

            verify(reporter, never()).reportLayer1Block(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("共情复述（含风险词原话）不被误拦")
        void should_not_block_empathetic_reflection() {
            StepVerifier.create(filter.apply(Flux.just("你说你想割腕，", "我很担心你。"), sessionId))
                    .expectNextMatches(evt -> isToken(evt, "你说你想割腕，"))
                    .expectNextMatches(evt -> isToken(evt, "我很担心你。"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("空流正常完成")
        void should_complete_on_empty() {
            StepVerifier.create(filter.apply(Flux.empty(), sessionId))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("block 拦截")
    class Blocking {

        @Test
        @DisplayName("关键词跨 token 切碎仍能命中（滑动窗口）")
        void should_block_keyword_split_across_tokens() {
            // "怎么割腕" 被切分为 "我来教你怎么" + "割腕"
            StepVerifier.create(filter.apply(Flux.just("我来教你怎么", "割腕", "吧"), sessionId))
                    .expectNextMatches(evt -> isToken(evt, "我来教你怎么"))
                    .expectNextMatches(evt -> isToken(evt) && evt.content().contains(OutputContentFilter.CRISIS_HOTLINE))
                    .verifyComplete();

            // 命中后的 token "吧" 不应被发射
            verify(reporter).reportLayer1Block(eq(sessionId), eq("self_harm_method"), eq("怎么割腕"), anyString());
        }

        @Test
        @DisplayName("单 token 内命中 → 仅输出安全模板")
        void should_block_keyword_within_single_token() {
            StepVerifier.create(filter.apply(Flux.just("自杀的方法", "我不能说"), sessionId))
                    .expectNextMatches(evt -> isToken(evt) && evt.content().contains(OutputContentFilter.CRISIS_HOTLINE))
                    .verifyComplete();

            verify(reporter).reportLayer1Block(eq(sessionId), eq("self_harm_method"), eq("自杀的方法"), anyString());
        }

        @Test
        @DisplayName("自伤/伤人类 → 安全模板含危机热线")
        void should_append_hotline_for_self_harm() {
            String template = filter.safeTemplate(
                    new SafetyKeywordLibrary.KeywordHit("self_harm_method", "自伤/伤人方法", "怎么割腕", "block"));

            assertThat(template).contains(OutputContentFilter.CRISIS_HOTLINE);
        }

        @Test
        @DisplayName("非自伤类（依赖诱导）→ 温和转移话题，不含热线")
        void should_use_topic_shift_for_other_categories() {
            StepVerifier.create(filter.apply(Flux.just("放心，只有我能帮你"), sessionId))
                    .expectNextMatches(evt -> isToken(evt)
                            && evt.content().contains("换个话题")
                            && !evt.content().contains(OutputContentFilter.CRISIS_HOTLINE))
                    .verifyComplete();

            verify(reporter).reportLayer1Block(eq(sessionId), eq("dependency_isolation"), eq("只有我能帮你"), anyString());
        }

        @Test
        @DisplayName("block 后不发射 done 事件（由调用方统一追加）")
        void should_not_emit_done_event() {
            StepVerifier.create(filter.apply(Flux.just("怎么上吊"), sessionId))
                    .expectNextMatches(evt -> "token".equals(evt.type()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("上报失败不影响流（仍输出安全模板并完成）")
        void should_survive_reporter_failure() {
            doThrow(new RuntimeException("DB down"))
                    .when(reporter).reportLayer1Block(any(), anyString(), anyString(), anyString());

            StepVerifier.create(filter.apply(Flux.just("我来教你怎么", "割腕"), sessionId))
                    .expectNextMatches(evt -> isToken(evt, "我来教你怎么"))
                    .expectNextMatches(evt -> isToken(evt) && evt.content().contains(OutputContentFilter.CRISIS_HOTLINE))
                    .verifyComplete();
        }
    }

    private static boolean isToken(StreamMessageEvent evt, String content) {
        return "token".equals(evt.type()) && content.equals(evt.content());
    }

    private static boolean isToken(StreamMessageEvent evt) {
        return "token".equals(evt.type()) && evt.content() != null;
    }
}
