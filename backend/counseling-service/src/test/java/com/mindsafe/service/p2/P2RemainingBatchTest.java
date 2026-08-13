package com.mindsafe.service.p2;

import com.mindsafe.service.knowledge.HybridRetrievalService;
import com.mindsafe.service.knowledge.HybridRetrievalService.*;
import com.mindsafe.service.memory.MemoryRiskCorrelator;
import com.mindsafe.service.memory.MemoryRiskCorrelator.*;
import com.mindsafe.service.prompt.PromptEvalGovernance;
import com.mindsafe.service.prompt.PromptEvalGovernance.*;
import com.mindsafe.service.voice.TrendAnomalySignaler;
import com.mindsafe.service.voice.TrendAnomalySignaler.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 剩余批次测试：PEVAL-004 + PROF-024 + VCL-003 + TMATCH-003 + MEM-103 + KB-103
 */
class P2RemainingBatchTest {

    // ==================== PEVAL-004 评估治理 ====================

    @Nested
    @DisplayName("PEVAL-004 评估治理")
    class PEVAL004 {

        private final PromptEvalGovernance gov = new PromptEvalGovernance();

        @Test
        @DisplayName("灰度放量：达标可进阶")
        void rolloutAdvance() {
            RolloutDecision d = gov.evaluateRollout(0, 0.95, 0.02, 0.01, 0.01);
            assertThat(d.canAdvance()).isTrue();
            assertThat(d.shouldRollback()).isFalse();
            assertThat(d.nextStagePercent()).isEqualTo(20);
        }

        @Test
        @DisplayName("灰度放量：安全破线→回滚")
        void rolloutSafetyBreak() {
            RolloutDecision d = gov.evaluateRollout(1, 0.80, 0.02, 0.01, 0.0);
            assertThat(d.shouldRollback()).isTrue();
            assertThat(d.reason()).contains("safety_compliance");
        }

        @Test
        @DisplayName("灰度放量：护栏拦截率突增→回滚")
        void rolloutGuardrailSpike() {
            RolloutDecision d = gov.evaluateRollout(1, 0.95, 0.10, 0.02, 0.0);
            assertThat(d.shouldRollback()).isTrue();
            assertThat(d.reason()).contains("突增");
        }

        @Test
        @DisplayName("灰度放量：eval 回退→回滚")
        void rolloutEvalRegress() {
            RolloutDecision d = gov.evaluateRollout(2, 0.95, 0.01, 0.01, -0.08);
            assertThat(d.shouldRollback()).isTrue();
            assertThat(d.reason()).contains("eval 分数回退");
        }

        @Test
        @DisplayName("已全量→不再进阶")
        void rolloutFull() {
            RolloutDecision d = gov.evaluateRollout(3, 0.95, 0.01, 0.01, 0.0);
            assertThat(d.canAdvance()).isFalse();
            assertThat(d.reason()).contains("已全量");
        }

        @Test
        @DisplayName("κ 校准：高一致性")
        void kappaHigh() {
            int[] judge = {1, 1, 0, 0, 1, 1, 0, 1, 0, 0};
            int[] human = {1, 1, 0, 0, 1, 0, 0, 1, 0, 0};
            KappaResult r = gov.computeKappa(judge, human, "judge-v1");
            assertThat(r.kappa()).isGreaterThan(0.6);
            assertThat(r.acceptable()).isTrue();
        }

        @Test
        @DisplayName("κ 校准：随机一致性低")
        void kappaLow() {
            int[] judge = {1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
            int[] human = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1};
            KappaResult r = gov.computeKappa(judge, human, "judge-v1");
            assertThat(r.kappa()).isLessThan(0);
            assertThat(r.acceptable()).isFalse();
        }

        @Test
        @DisplayName("人群下钻聚合")
        void drillDown() {
            List<EvalRecord> records = List.of(
                    new EvalRecord("low", "happy", "introvert", 0.8, 0.7, 0.9, 0.6),
                    new EvalRecord("low", "sad", "introvert", 0.7, 0.6, 0.9, 0.5),
                    new EvalRecord("high", "happy", "extrovert", 0.9, 0.8, 0.95, 0.8)
            );
            List<EvalAggregate> byGrade = gov.drillDown(records, DrillDimension.GRADE_BAND);
            assertThat(byGrade).hasSize(2);
            // low 段有 2 条
            EvalAggregate low = byGrade.stream()
                    .filter(a -> a.segment().equals("low")).findFirst().orElseThrow();
            assertThat(low.sampleCount()).isEqualTo(2);
            assertThat(low.empathyMean()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("分段效果差距告警")
        void underperforming() {
            assertThat(gov.isSegmentUnderperforming(0.6, 0.8)).isTrue();
            assertThat(gov.isSegmentUnderperforming(0.75, 0.8)).isFalse();
        }
    }

    // ==================== VCL-003 趋势异常 ====================

    @Nested
    @DisplayName("VCL-003 趋势异常信号")
    class VCL003 {

        private final TrendAnomalySignaler signaler = new TrendAnomalySignaler();

        @Test
        @DisplayName("连续恶化+高负面→关注信号")
        void worseningSignal() {
            AttentionSignal s = signaler.evaluate("s1", 4, 0.8, 0.7);
            assertThat(s).isNotNull();
            assertThat(s.signalType()).isEqualTo("WORSENING_HIGH_NEGATIVE");
            assertThat(s.suggestScaleRetest()).isTrue();
            assertThat(s.routeToTeacher()).isTrue();
        }

        @Test
        @DisplayName("低置信→不生成信号")
        void lowConfidence() {
            AttentionSignal s = signaler.evaluate("s1", 5, 0.9, 0.3);
            assertThat(s).isNull();
        }

        @Test
        @DisplayName("正常趋势→无信号")
        void normalTrend() {
            AttentionSignal s = signaler.evaluate("s1", 1, 0.3, 0.8);
            assertThat(s).isNull();
        }

        @Test
        @DisplayName("SER 准确度评估")
        void serAccuracy() {
            List<String> preds = List.of("happy", "sad", "happy", "angry", "sad",
                    "happy", "sad", "angry", "happy", "sad");
            List<String> labels = List.of("happy", "sad", "happy", "angry", "happy",
                    "happy", "sad", "fearful", "happy", "sad");
            SerAccuracyReport r = signaler.evaluateAccuracy(preds, labels);
            assertThat(r.totalSamples()).isEqualTo(10);
            assertThat(r.accuracy()).isEqualTo(0.8);
            assertThat(r.topConfusions()).isNotEmpty();
        }

        @Test
        @DisplayName("阈值自适应：低精确率→提高")
        void adaptUp() {
            ThresholdConfig c = signaler.adaptThreshold("fearful", 0.6, 0.5, 20);
            assertThat(c.threshold()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("阈值自适应：高精确率→降低")
        void adaptDown() {
            ThresholdConfig c = signaler.adaptThreshold("happy", 0.7, 0.95, 50);
            assertThat(c.threshold()).isCloseTo(0.65, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("样本不足→不调")
        void adaptInsufficient() {
            ThresholdConfig c = signaler.adaptThreshold("angry", 0.6, 0.5, 5);
            assertThat(c.threshold()).isEqualTo(0.6);
        }
    }

    // ==================== MEM-103 记忆风险 ====================

    @Nested
    @DisplayName("MEM-103 记忆风险+遗忘")
    class MEM103 {

        private final MemoryRiskCorrelator correlator = new MemoryRiskCorrelator();
        private final Instant now = Instant.parse("2026-07-28T00:00:00Z");

        @Test
        @DisplayName("负面主题≥3→关注信号")
        void riskSignal() {
            List<ThemeOccurrence> occs = List.of(
                    new ThemeOccurrence("loneliness", true, now.minus(5, ChronoUnit.DAYS)),
                    new ThemeOccurrence("loneliness", true, now.minus(10, ChronoUnit.DAYS)),
                    new ThemeOccurrence("loneliness", true, now.minus(15, ChronoUnit.DAYS)),
                    new ThemeOccurrence("friendship", false, now.minus(3, ChronoUnit.DAYS))
            );
            RiskSignal s = correlator.correlateRisk("s1", occs, now);
            assertThat(s).isNotNull();
            assertThat(s.theme()).isEqualTo("loneliness");
            assertThat(s.occurrenceCount()).isEqualTo(3);
            assertThat(s.signalLevel()).isEqualTo("WATCH");
        }

        @Test
        @DisplayName("≥5次→ELEVATED+建议复测")
        void elevated() {
            List<ThemeOccurrence> occs = List.of(
                    new ThemeOccurrence("bullying", true, now.minus(2, ChronoUnit.DAYS)),
                    new ThemeOccurrence("bullying", true, now.minus(5, ChronoUnit.DAYS)),
                    new ThemeOccurrence("bullying", true, now.minus(8, ChronoUnit.DAYS)),
                    new ThemeOccurrence("bullying", true, now.minus(12, ChronoUnit.DAYS)),
                    new ThemeOccurrence("bullying", true, now.minus(20, ChronoUnit.DAYS))
            );
            RiskSignal s = correlator.correlateRisk("s1", occs, now);
            assertThat(s.signalLevel()).isEqualTo("ELEVATED");
            assertThat(s.suggestScaleRetest()).isTrue();
        }

        @Test
        @DisplayName("窗口外不计入")
        void outsideWindow() {
            List<ThemeOccurrence> occs = List.of(
                    new ThemeOccurrence("sad", true, now.minus(40, ChronoUnit.DAYS)),
                    new ThemeOccurrence("sad", true, now.minus(50, ChronoUnit.DAYS)),
                    new ThemeOccurrence("sad", true, now.minus(60, ChronoUnit.DAYS))
            );
            assertThat(correlator.correlateRisk("s1", occs, now)).isNull();
        }

        @Test
        @DisplayName("遗忘：学生意愿最高优先")
        void forgetStudentWill() {
            MemoryEntry e = new MemoryEntry("m1", 0.9, false, true,
                    now, now, true);
            ForgetDecision d = correlator.evaluateForget(e, now);
            assertThat(d.shouldForget()).isTrue();
            assertThat(d.action()).isEqualTo("DELETE");
        }

        @Test
        @DisplayName("遗忘：recurring 保留")
        void forgetRecurringKeep() {
            MemoryEntry e = new MemoryEntry("m1", 0.2, false, false,
                    now.minus(100, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS), true);
            ForgetDecision d = correlator.evaluateForget(e, now);
            assertThat(d.shouldForget()).isFalse();
        }

        @Test
        @DisplayName("遗忘：高敏感超期→泛化删除")
        void forgetSensitive() {
            MemoryEntry e = new MemoryEntry("m1", 0.5, true, false,
                    now.minus(10, ChronoUnit.DAYS), now.minus(40, ChronoUnit.DAYS), false);
            ForgetDecision d = correlator.evaluateForget(e, now);
            assertThat(d.shouldForget()).isTrue();
            assertThat(d.action()).isEqualTo("GENERALIZE_THEN_DELETE");
        }

        @Test
        @DisplayName("遗忘：久未召回+低重要性→归档")
        void forgetStale() {
            MemoryEntry e = new MemoryEntry("m1", 0.2, false, false,
                    now.minus(100, ChronoUnit.DAYS), now.minus(150, ChronoUnit.DAYS), false);
            ForgetDecision d = correlator.evaluateForget(e, now);
            assertThat(d.shouldForget()).isTrue();
            assertThat(d.action()).isEqualTo("ARCHIVE");
        }

        @Test
        @DisplayName("双向互哺权重")
        void mutualWeight() {
            double recurring = correlator.memoryToProfileWeight(true, 5, 3);
            double single = correlator.memoryToProfileWeight(false, 1, 3);
            assertThat(recurring).isGreaterThan(single);

            double boost = correlator.profileToMemoryRecallBoost(0.8, 0.9);
            assertThat(boost).isGreaterThan(1.0);
        }
    }

    // ==================== KB-103 混合检索 ====================

    @Nested
    @DisplayName("KB-103 混合检索 RRF")
    class KB103 {

        private final HybridRetrievalService service = new HybridRetrievalService();

        @Test
        @DisplayName("RRF 融合：双路命中排前")
        void rrfFusion() {
            List<RetrievalHit> vector = List.of(
                    new RetrievalHit("d1", "CBT基础", 0.9, "vector"),
                    new RetrievalHit("d2", "呼吸练习", 0.8, "vector"),
                    new RetrievalHit("d3", "情绪日记", 0.7, "vector")
            );
            List<RetrievalHit> keyword = List.of(
                    new RetrievalHit("d2", "呼吸练习", 5.0, "keyword"),
                    new RetrievalHit("d4", "系统脱敏", 4.0, "keyword")
            );
            List<FusedResult> fused = service.fuseRRF(vector, keyword, 3);
            assertThat(fused).hasSize(3);
            // d2 双路命中，RRF 分最高
            assertThat(fused.get(0).docId()).isEqualTo("d2");
            assertThat(fused.get(0).fromVector()).isTrue();
            assertThat(fused.get(0).fromKeyword()).isTrue();
        }

        @Test
        @DisplayName("RRF：空输入")
        void rrfEmpty() {
            assertThat(service.fuseRRF(null, null, 5)).isEmpty();
            assertThat(service.fuseRRF(List.of(), List.of(), 5)).isEmpty();
        }

        @Test
        @DisplayName("内容缺口识别")
        void contentGaps() {
            List<String> missed = List.of(
                    "如何交朋友", "如何交朋友", "如何交朋友",
                    "考试焦虑怎么办", "考试焦虑怎么办", "考试焦虑怎么办", "考试焦虑怎么办",
                    "呼吸放松", "呼吸放松"
            );
            List<ContentGap> gaps = service.identifyContentGaps(missed);
            assertThat(gaps).hasSize(2); // 呼吸放松只有2次，不够3次
            assertThat(gaps.get(0).query()).isEqualTo("考试焦虑怎么办");
            assertThat(gaps.get(0).missCount()).isEqualTo(4);
        }
    }
}
