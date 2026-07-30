package com.mindsafe.service.memory;

import com.mindsafe.service.memory.ThemeEvolutionEngine.EventSnippet;
import com.mindsafe.service.memory.ThemeEvolutionEngine.ThemeCandidate;
import com.mindsafe.service.memory.ThemeEvolutionEngine.Trend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEM-102 多因子召回 + 主题演化 单元测试
 */
class MemoryEnhancementTest {

    private final MemoryRelevanceScorer scorer = new MemoryRelevanceScorer();
    private final ThemeEvolutionEngine engine = new ThemeEvolutionEngine();

    // ===== 多因子召回评分 =====

    @Nested
    @DisplayName("MemoryRelevanceScorer")
    class ScorerTests {

        private final Instant now = Instant.parse("2026-07-28T10:00:00Z");

        @Test
        @DisplayName("recurring_theme 比 key_event 得分高（同条件）")
        void recurringBoost() {
            double scoreRecurring = scorer.score(0.8, 0.7, now.minus(1, ChronoUnit.DAYS), "recurring_theme", now);
            double scoreKey = scorer.score(0.8, 0.7, now.minus(1, ChronoUnit.DAYS), "key_event", now);
            assertThat(scoreRecurring).isGreaterThan(scoreKey);
            assertThat(scoreRecurring - scoreKey).isCloseTo(0.20, org.assertj.core.data.Offset.offset(0.001)); // W_RECURRING
        }

        @Test
        @DisplayName("时效衰减：14 天后 recency=0.5")
        void recency_halfLife() {
            Instant created = now.minus(14, ChronoUnit.DAYS);
            double recency = scorer.computeRecency(created, now);
            assertThat(recency).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("时效衰减：刚创建=1.0")
        void recency_fresh() {
            assertThat(scorer.computeRecency(now, now)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("时效衰减：28 天=0.25")
        void recency_28days() {
            Instant created = now.minus(28, ChronoUnit.DAYS);
            double recency = scorer.computeRecency(created, now);
            assertThat(recency).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("null 时间 → recency=0.5（安全降级）")
        void recency_null() {
            assertThat(scorer.computeRecency(null, now)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("综合分范围 0~1")
        void score_bounded() {
            double max = scorer.score(1.0, 1.0, now, "recurring_theme", now);
            double min = scorer.score(0.0, 0.0, now.minus(365, ChronoUnit.DAYS), "key_event", now);
            assertThat(max).isLessThanOrEqualTo(1.0);
            assertThat(min).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("isWorthRecalling 阈值 0.3")
        void worthRecalling() {
            assertThat(scorer.isWorthRecalling(0.3)).isTrue();
            assertThat(scorer.isWorthRecalling(0.29)).isFalse();
        }

        @Test
        @DisplayName("高相关+高重要+新鲜+recurring → 高分")
        void idealScore() {
            double score = scorer.score(0.95, 0.9, now, "recurring_theme", now);
            // 0.35*0.95 + 0.25*0.9 + 0.20*1.0 + 0.20*1.0 = 0.3325+0.225+0.2+0.2 = 0.9575
            assertThat(score).isCloseTo(0.9575, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    // ===== 主题演化 =====

    @Nested
    @DisplayName("ThemeEvolutionEngine")
    class ThemeTests {

        @Test
        @DisplayName("同伴冲突 ≥3 次 → 识别主题")
        void peerConflict_detected() {
            Instant base = Instant.parse("2026-07-01T10:00:00Z");
            List<EventSnippet> events = List.of(
                    new EventSnippet("和同桌吵架了", "angry", base),
                    new EventSnippet("同学不跟我玩", "sad", base.plus(3, ChronoUnit.DAYS)),
                    new EventSnippet("朋友都不理我了", "sad", base.plus(7, ChronoUnit.DAYS))
            );

            List<ThemeCandidate> themes = engine.identifyThemes(events);
            assertThat(themes).hasSize(1);
            assertThat(themes.get(0).themeKey()).isEqualTo("peer_conflict");
            assertThat(themes.get(0).occurrenceCount()).isEqualTo(3);
            assertThat(themes.get(0).dominantEmotion()).isEqualTo("sad");
        }

        @Test
        @DisplayName("不足 3 次 → 不触发")
        void belowThreshold() {
            List<EventSnippet> events = List.of(
                    new EventSnippet("和同桌吵架了", "angry", Instant.now()),
                    new EventSnippet("同学不跟我玩", "sad", Instant.now())
            );
            assertThat(engine.identifyThemes(events)).isEmpty();
        }

        @Test
        @DisplayName("null/空列表 → 空结果")
        void nullEvents() {
            assertThat(engine.identifyThemes(null)).isEmpty();
            assertThat(engine.identifyThemes(List.of())).isEmpty();
        }

        @Test
        @DisplayName("多主题同时识别，按次数降序")
        void multipleThemes() {
            Instant base = Instant.parse("2026-07-01T10:00:00Z");
            List<EventSnippet> events = new ArrayList<>();
            // 学业压力 4 次
            events.add(new EventSnippet("考试没考好", "sad", base));
            events.add(new EventSnippet("作业太多了", "anxious", base.plus(1, ChronoUnit.DAYS)));
            events.add(new EventSnippet("成绩下降了", "sad", base.plus(2, ChronoUnit.DAYS)));
            events.add(new EventSnippet("补课好累", "tired", base.plus(3, ChronoUnit.DAYS)));
            // 同伴冲突 3 次
            events.add(new EventSnippet("和同桌吵架", "angry", base));
            events.add(new EventSnippet("被同学排挤", "sad", base.plus(1, ChronoUnit.DAYS)));
            events.add(new EventSnippet("朋友不理我", "sad", base.plus(2, ChronoUnit.DAYS)));

            List<ThemeCandidate> themes = engine.identifyThemes(events);
            assertThat(themes).hasSize(2);
            assertThat(themes.get(0).themeKey()).isEqualTo("academic_pressure");
            assertThat(themes.get(0).occurrenceCount()).isEqualTo(4);
            assertThat(themes.get(1).themeKey()).isEqualTo("peer_conflict");
        }

        @Test
        @DisplayName("趋势判断：近期集中 → ESCALATING")
        void escalating_trend() {
            Instant base = Instant.parse("2026-06-01T10:00:00Z");
            List<EventSnippet> events = List.of(
                    new EventSnippet("考试1", "sad", base),
                    new EventSnippet("考试2", "sad", base.plus(25, ChronoUnit.DAYS)),
                    new EventSnippet("考试3", "sad", base.plus(26, ChronoUnit.DAYS)),
                    new EventSnippet("考试4", "sad", base.plus(27, ChronoUnit.DAYS)),
                    new EventSnippet("考试5", "sad", base.plus(28, ChronoUnit.DAYS)),
                    new EventSnippet("考试6", "sad", base.plus(29, ChronoUnit.DAYS))
            );
            List<ThemeCandidate> themes = engine.identifyThemes(events);
            assertThat(themes).hasSize(1);
            assertThat(themes.get(0).trend()).isEqualTo(Trend.ESCALATING);
        }

        @Test
        @DisplayName("generateThemeContent 中性泛化表述")
        void themeContent() {
            ThemeCandidate candidate = new ThemeCandidate(
                    "peer_conflict", "同伴关系困扰", 4,
                    java.util.Set.of("同桌", "吵架"), "sad", Trend.ESCALATING);
            String content = engine.generateThemeContent(candidate);
            assertThat(content).contains("同伴关系困扰");
            assertThat(content).contains("4 次");
            assertThat(content).contains("加剧");
            assertThat(content).doesNotContain("诊断");
        }
    }
}
