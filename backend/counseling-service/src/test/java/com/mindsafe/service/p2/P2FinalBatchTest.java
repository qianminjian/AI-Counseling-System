package com.mindsafe.service.p2;

import com.mindsafe.ai.orchestrator.EmotionOrchestrationEvaluator;
import com.mindsafe.ai.orchestrator.EmotionOrchestrationEvaluator.*;
import com.mindsafe.service.casemanage.CaseLifecycleService;
import com.mindsafe.service.casemanage.CaseLifecycleService.*;
import com.mindsafe.service.tts.TtsPipelineScheduler;
import com.mindsafe.service.tts.TtsPipelineScheduler.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 §近期偏后批次测试：WB-003 + TTSFX-003 + ORCH-008
 */
class P2FinalBatchTest {

    // ==================== WB-003 个案管理 ====================

    @Nested
    @DisplayName("WB-003 个案管理")
    class WB003 {

        private final CaseLifecycleService service = new CaseLifecycleService();

        @Test
        @DisplayName("顺序推进：INTAKE→ASSESSMENT 允许")
        void normalTransition() {
            StageTransition t = service.transition(CaseStage.INTAKE, CaseStage.ASSESSMENT, false);
            assertThat(t.allowed()).isTrue();
        }

        @Test
        @DisplayName("跳级推进：INTAKE→INTERVENTION 拒绝")
        void skipTransition() {
            StageTransition t = service.transition(CaseStage.INTAKE, CaseStage.INTERVENTION, false);
            assertThat(t.allowed()).isFalse();
            assertThat(t.reason()).contains("不可跳级");
        }

        @Test
        @DisplayName("结案无小结→拒绝")
        void closeNoSummary() {
            StageTransition t = service.transition(CaseStage.INTERVENTION, CaseStage.CLOSED, false);
            assertThat(t.allowed()).isFalse();
            assertThat(t.reason()).contains("结案小结");
        }

        @Test
        @DisplayName("结案有小结→允许")
        void closeWithSummary() {
            StageTransition t = service.transition(CaseStage.INTERVENTION, CaseStage.CLOSED, true);
            assertThat(t.allowed()).isTrue();
        }

        @Test
        @DisplayName("随访到期→生成待办")
        void followupDue() {
            Instant now = Instant.parse("2026-07-28T10:00:00Z");
            Instant due = now.minus(2, ChronoUnit.HOURS);
            FollowupTodo todo = service.checkFollowupDue("c1", "s1", due, now);
            assertThat(todo).isNotNull();
            assertThat(todo.overdue()).isTrue();
            assertThat(todo.overdueMinutes()).isEqualTo(120);
        }

        @Test
        @DisplayName("随访明天→不进入今日待办")
        void followupTomorrow() {
            Instant now = Instant.parse("2026-07-28T10:00:00Z");
            Instant tomorrow = now.plus(2, ChronoUnit.DAYS);
            assertThat(service.checkFollowupDue("c1", "s1", tomorrow, now)).isNull();
        }

        @Test
        @DisplayName("测评中度→建议建案")
        void scaleReferral() {
            assertThat(service.shouldReferToCase(2)).isTrue();
            assertThat(service.shouldReferToCase(1)).isFalse();
        }

        @Test
        @DisplayName("结案→终止复测")
        void terminateRetest() {
            assertThat(service.shouldTerminateRetest(CaseStage.CLOSED)).isTrue();
            assertThat(service.shouldTerminateRetest(CaseStage.INTERVENTION)).isFalse();
        }

        @Test
        @DisplayName("批量待办：结案不生成")
        void batchFollowups() {
            Instant now = Instant.parse("2026-07-28T10:00:00Z");
            List<CaseSummary> cases = List.of(
                    new CaseSummary("c1", "s1", CaseStage.INTERVENTION, CaseSource.MANUAL,
                            now.minus(1, ChronoUnit.HOURS), now.minus(30, ChronoUnit.DAYS), null),
                    new CaseSummary("c2", "s2", CaseStage.CLOSED, CaseSource.ALERT_TRANSFER,
                            now.minus(1, ChronoUnit.HOURS), now.minus(60, ChronoUnit.DAYS), "已结案")
            );
            List<FollowupTodo> todos = service.getDueFollowups(cases, now);
            assertThat(todos).hasSize(1);
            assertThat(todos.get(0).caseId()).isEqualTo("c1");
        }
    }

    // ==================== TTSFX-003 延迟流水线 ====================

    @Nested
    @DisplayName("TTSFX-003 延迟流水线+降级")
    class TTSFX003 {

        private final TtsPipelineScheduler scheduler = new TtsPipelineScheduler();

        @Test
        @DisplayName("首句：立即播放")
        void firstSentence() {
            ScheduleResult r = scheduler.schedule(new SentenceTask(0, "你好呀", true, false));
            assertThat(r.immediatePlay()).isTrue();
            assertThat(r.parallelSynth()).isFalse();
        }

        @Test
        @DisplayName("后续句：并行合成")
        void subsequentSentence() {
            ScheduleResult r = scheduler.schedule(new SentenceTask(2, "第三句", false, false));
            assertThat(r.immediatePlay()).isFalse();
            assertThat(r.parallelSynth()).isTrue();
        }

        @Test
        @DisplayName("缓存命中：零延迟")
        void cacheHit() {
            ScheduleResult r = scheduler.schedule(new SentenceTask(0, "危机话术", true, true));
            assertThat(r.immediatePlay()).isTrue();
            assertThat(r.strategy()).contains("零延迟");
        }

        @Test
        @DisplayName("首音频预算")
        void budget() {
            assertThat(scheduler.isFirstAudioWithinBudget(1200)).isTrue();
            assertThat(scheduler.isFirstAudioWithinBudget(1600)).isFalse();
        }

        @Test
        @DisplayName("帧率正常→全效果")
        void fullPerformance() {
            DegradeDecision d = scheduler.evaluatePerformance(List.of(30, 29, 30, 28, 30), false);
            assertThat(d.level()).isEqualTo(DegradeLevel.FULL);
            assertThat(d.lottieEnabled()).isTrue();
        }

        @Test
        @DisplayName("帧率连续低→自动降级")
        void autoDegrade() {
            DegradeDecision d = scheduler.evaluatePerformance(List.of(20, 18, 22, 20, 19), false);
            assertThat(d.level()).isEqualTo(DegradeLevel.DEGRADED);
            assertThat(d.lottieEnabled()).isFalse();
            assertThat(d.particlesEnabled()).isFalse();
        }

        @Test
        @DisplayName("用户手动关闭")
        void userDisabled() {
            DegradeDecision d = scheduler.evaluatePerformance(List.of(60, 60, 60, 60, 60), true);
            assertThat(d.level()).isEqualTo(DegradeLevel.DEGRADED);
            assertThat(d.reason()).contains("手动");
        }

        @Test
        @DisplayName("降级替代方案")
        void fallbacks() {
            assertThat(scheduler.getFallback("lottie", true)).isEqualTo("static_first_frame");
            assertThat(scheduler.getFallback("particle", true)).isEqualTo("disabled");
            assertThat(scheduler.getFallback("breathing_guide", true)).isEqualTo("countdown_numeric");
            assertThat(scheduler.getFallback("lottie", false)).isEqualTo("lottie");
        }
    }

    // ==================== ORCH-008 情绪编排效果 ====================

    @Nested
    @DisplayName("ORCH-008 情绪编排效果量化")
    class ORCH008 {

        private final EmotionOrchestrationEvaluator evaluator = new EmotionOrchestrationEvaluator();

        @Test
        @DisplayName("回落速度：3轮稳定")
        void recovery() {
            List<String> states = List.of("ACTIVATED", "ACTIVATED", "ACTIVATED", "STABLE", "STABLE");
            RecoveryResult r = evaluator.measureRecovery(states);
            assertThat(r.recovered()).isTrue();
            assertThat(r.turnsToStable()).isEqualTo(3);
        }

        @Test
        @DisplayName("未回落")
        void noRecovery() {
            List<String> states = List.of("ACTIVATED", "ACTIVATED", "ACTIVATED");
            RecoveryResult r = evaluator.measureRecovery(states);
            assertThat(r.recovered()).isFalse();
        }

        @Test
        @DisplayName("会话深度：排除短消息")
        void depth() {
            List<String> msgs = List.of("好的", "我今天很不开心因为同学", "嗯", "是的他总是不理我");
            assertThat(evaluator.measureDepth(msgs)).isEqualTo(2);
        }

        @Test
        @DisplayName("适配判定：sad+empathy=适配")
        void fitSad() {
            FitAssessment f = evaluator.assessFit("sad", "empathy_mirror");
            assertThat(f.adapted()).isTrue();
        }

        @Test
        @DisplayName("适配判定：angry+grounding=不适配")
        void fitAngryWrong() {
            FitAssessment f = evaluator.assessFit("angry", "grounding_exercise");
            assertThat(f.adapted()).isFalse();
        }

        @Test
        @DisplayName("适配判定：anxious+breathing=适配")
        void fitAnxious() {
            FitAssessment f = evaluator.assessFit("anxious", "breathing_guide");
            assertThat(f.adapted()).isTrue();
        }

        @Test
        @DisplayName("综合对比：适配有效")
        void effectiveComparison() {
            EffectComparison c = evaluator.compare(
                    List.of(2.0, 3.0, 2.5), List.of(5.0, 6.0, 5.5),  // 回落更快
                    List.of(8.0, 9.0, 7.0), List.of(5.0, 4.0, 6.0),  // 深度更深
                    List.of(2.5, 2.8, 2.6), List.of(2.0, 1.8, 2.1)   // 满意度更高
            );
            assertThat(c.emotionAdaptationEffective()).isTrue();
        }

        @Test
        @DisplayName("综合对比：适配无效")
        void ineffectiveComparison() {
            EffectComparison c = evaluator.compare(
                    List.of(5.0, 6.0), List.of(3.0, 4.0),  // 回落更慢
                    List.of(4.0, 5.0), List.of(6.0, 7.0),  // 深度更浅
                    List.of(1.5, 1.8), List.of(2.5, 2.8)   // 满意度更低
            );
            assertThat(c.emotionAdaptationEffective()).isFalse();
        }
    }
}
