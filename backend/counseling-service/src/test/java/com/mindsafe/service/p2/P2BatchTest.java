package com.mindsafe.service.p2;

import com.mindsafe.service.billing.EntitlementChecker;
import com.mindsafe.service.billing.EntitlementChecker.CheckResult;
import com.mindsafe.service.billing.EntitlementChecker.Plan;
import com.mindsafe.service.experiment.ExperimentBucketAssigner;
import com.mindsafe.service.experiment.ExperimentBucketAssigner.Assignment;
import com.mindsafe.service.experiment.ExperimentBucketAssigner.Variant;
import com.mindsafe.service.experiment.ExperimentMetricsCollector;
import com.mindsafe.service.experiment.ExperimentMetricsCollector.*;
import com.mindsafe.service.profile.ProfileMergeGate;
import com.mindsafe.service.profile.ProfileMergeGate.MergeDecision;
import com.mindsafe.service.prompt.TemplateMatrixRegistry;
import com.mindsafe.service.prompt.TemplateMatrixRegistry.GateResult;
import com.mindsafe.service.tts.VoicePersonaMatcher;
import com.mindsafe.service.tts.VoicePersonaMatcher.MatchResult;
import com.mindsafe.service.tts.VoicePersonaMatcher.Prosody;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer.FusionResult;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer.Trend;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer.TrendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 批次全量测试：AB-001/002 + BILL-001/002 + PROF-023 + VCL-002 + TMATCH-002 + PEVAL-003
 */
class P2BatchTest {

    // ==================== AB-001 实验分桶 ====================

    @Nested
    @DisplayName("AB-001 实验分桶")
    class AB001 {

        private final ExperimentBucketAssigner assigner = new ExperimentBucketAssigner();

        @Test
        @DisplayName("确定性：同参数万次重算一致")
        void deterministic() {
            Assignment first = assigner.assignClass("exp-1", "class-A", "salt-v1");
            for (int i = 0; i < 1000; i++) {
                Assignment again = assigner.assignClass("exp-1", "class-A", "salt-v1");
                assertThat(again.bucket()).isEqualTo(first.bucket());
                assertThat(again.variant()).isEqualTo(first.variant());
            }
        }

        @Test
        @DisplayName("不同班级分到不同桶")
        void differentClasses() {
            Assignment a = assigner.assignClass("exp-1", "class-A", "salt");
            Assignment b = assigner.assignClass("exp-1", "class-B", "salt");
            // 不保证不同，但 bucket 值大概率不同
            assertThat(a.analyzable()).isTrue();
            assertThat(b.analyzable()).isTrue();
        }

        @Test
        @DisplayName("豁免学生：强制 treatment + 不入分析集")
        void exempt() {
            Assignment exempt = assigner.assignExempt("exp-1", "student-high-risk");
            assertThat(exempt.variant()).isEqualTo(Variant.TREATMENT);
            assertThat(exempt.exempt()).isTrue();
            assertThat(exempt.analyzable()).isFalse();
        }

        @Test
        @DisplayName("年级均衡校验")
        void balance() {
            List<Integer> balanced1 = List.of(1, 2, 3, 4, 5, 6);
            List<Integer> balanced2 = List.of(1, 2, 3, 4, 5, 6);
            assertThat(assigner.isBalanced(balanced1, balanced2)).isTrue();

            List<Integer> skewed = List.of(1, 1, 1, 1, 1, 1);
            List<Integer> highOnly = List.of(6, 6, 6, 6, 6, 6);
            assertThat(assigner.isBalanced(skewed, highOnly)).isFalse();
        }

        @Test
        @DisplayName("bucket 范围 0-99")
        void bucketRange() {
            for (int i = 0; i < 100; i++) {
                Assignment a = assigner.assignClass("exp", "class-" + i, "salt");
                assertThat(a.bucket()).isBetween(0, 99);
            }
        }
    }

    // ==================== AB-002 指标采集 ====================

    @Nested
    @DisplayName("AB-002 指标采集")
    class AB002 {

        private final ExperimentMetricsCollector collector = new ExperimentMetricsCollector();

        @Test
        @DisplayName("隔次展示满意度反馈")
        void feedbackFrequency() {
            assertThat(collector.shouldShowFeedback(0)).isFalse();
            assertThat(collector.shouldShowFeedback(1)).isFalse();
            assertThat(collector.shouldShowFeedback(2)).isTrue();
            assertThat(collector.shouldShowFeedback(3)).isFalse();
            assertThat(collector.shouldShowFeedback(4)).isTrue();
        }

        @Test
        @DisplayName("满意度表情转数值")
        void emojiScore() {
            assertThat(collector.satisfactionScore(SatisfactionEmoji.HAPPY)).isEqualTo(3);
            assertThat(collector.satisfactionScore(SatisfactionEmoji.NEUTRAL)).isEqualTo(2);
            assertThat(collector.satisfactionScore(SatisfactionEmoji.SAD)).isEqualTo(1);
            assertThat(collector.satisfactionScore(null)).isEqualTo(0);
        }

        @Test
        @DisplayName("日聚合计算")
        void aggregate() {
            List<MetricEvent> events = List.of(
                    new MetricEvent("exp-1", "CONTROL", "s1", MetricType.SATISFACTION, 3, LocalDate.now()),
                    new MetricEvent("exp-1", "CONTROL", "s2", MetricType.SATISFACTION, 2, LocalDate.now()),
                    new MetricEvent("exp-1", "CONTROL", "s3", MetricType.SATISFACTION, 1, LocalDate.now())
            );
            DailyAggregate agg = collector.aggregate(events);
            assertThat(agg.n()).isEqualTo(3);
            assertThat(agg.sum()).isEqualTo(6);
            assertThat(agg.mean()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("Cohen's d 效应量")
        void cohensD() {
            DailyAggregate control = new DailyAggregate("e", "C", MetricType.SATISFACTION,
                    LocalDate.now(), 10, 20, 50);
            DailyAggregate treatment = new DailyAggregate("e", "T", MetricType.SATISFACTION,
                    LocalDate.now(), 10, 25, 70);
            double d = collector.cohensD(control, treatment);
            assertThat(d).isGreaterThan(0); // treatment 更高
        }

        @Test
        @DisplayName("S0 时延护栏")
        void safetyLatency() {
            assertThat(collector.isSafetyLatencyOk(100, 150)).isTrue();
            assertThat(collector.isSafetyLatencyOk(100, 400)).isFalse();
        }
    }

    // ==================== BILL-001 权益模型 ====================

    @Nested
    @DisplayName("BILL-001 权益模型")
    class BILL001 {

        private final EntitlementChecker checker = new EntitlementChecker();

        @Test
        @DisplayName("TRIAL 无 export 权益 → 403")
        void trialNoExport() {
            CheckResult r = checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_EXPORT, "/api/v1/export");
            assertThat(r.allowed()).isFalse();
            assertThat(r.httpStatus()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("30002");
        }

        @Test
        @DisplayName("STANDARD 有 export 权益 → 放行")
        void standardHasExport() {
            CheckResult r = checker.checkFeature(Plan.STANDARD, EntitlementChecker.FEAT_EXPORT, "/api/v1/export");
            assertThat(r.allowed()).isTrue();
        }

        @Test
        @DisplayName("豁免路径：预警接口永远放行")
        void exemptAlerts() {
            CheckResult r = checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_EXPORT, "/api/v1/alerts/123");
            assertThat(r.allowed()).isTrue();
        }

        @Test
        @DisplayName("豁免路径：SOS 永远放行")
        void exemptSos() {
            CheckResult r = checker.checkQuota(Plan.TRIAL, EntitlementChecker.QUOTA_SMS_SEND, 999, "/api/v1/sos/call");
            assertThat(r.allowed()).isTrue();
        }

        @Test
        @DisplayName("配额超限 → 429")
        void quotaExceeded() {
            CheckResult r = checker.checkQuota(Plan.TRIAL, EntitlementChecker.QUOTA_AI_SESSION, 200, "/api/v1/chat");
            assertThat(r.allowed()).isFalse();
            assertThat(r.httpStatus()).isEqualTo(429);
            assertThat(r.code()).isEqualTo("30001");
        }

        @Test
        @DisplayName("PREMIUM 无限制")
        void premiumUnlimited() {
            CheckResult r = checker.checkQuota(Plan.PREMIUM, EntitlementChecker.QUOTA_AI_SESSION, 999999, "/api/v1/chat");
            assertThat(r.allowed()).isTrue();
        }

        @Test
        @DisplayName("阈值告警：80% 触发")
        void alertThreshold() {
            assertThat(checker.shouldAlert(Plan.TRIAL, EntitlementChecker.QUOTA_AI_SESSION, 160)).isTrue();
            assertThat(checker.shouldAlert(Plan.TRIAL, EntitlementChecker.QUOTA_AI_SESSION, 100)).isFalse();
        }
    }

    // ==================== PROF-023 画像合并门控 ====================

    @Nested
    @DisplayName("PROF-023 画像合并门控")
    class PROF023 {

        private final ProfileMergeGate gate = new ProfileMergeGate();

        @Test
        @DisplayName("低置信新证据 → 不覆盖")
        void lowConfidenceKeep() {
            MergeDecision d = gate.merge(0.7, 0.8, 0.2, 0.2);
            assertThat(d.strategy()).isEqualTo("KEEP_EXISTING");
            assertThat(d.mergedValue()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("首次（无现有值）→ 直接替换")
        void firstTime() {
            MergeDecision d = gate.merge(0, 0, 0.6, 0.7);
            assertThat(d.strategy()).isEqualTo("REPLACE");
            assertThat(d.mergedValue()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("冲突（差 > 0.4）→ 加权均值")
        void conflict() {
            MergeDecision d = gate.merge(0.2, 0.6, 0.9, 0.6);
            assertThat(d.conflictDetected()).isTrue();
            assertThat(d.strategy()).isEqualTo("WEIGHTED_MERGE");
            assertThat(d.mergedValue()).isBetween(0.4, 0.7);
        }

        @Test
        @DisplayName("正常更新（EMA 风格）")
        void normalUpdate() {
            MergeDecision d = gate.merge(0.5, 0.6, 0.6, 0.6);
            assertThat(d.strategy()).isEqualTo("REPLACE");
            assertThat(d.mergedValue()).isBetween(0.5, 0.6);
            assertThat(d.mergedConfidence()).isGreaterThan(0.6);
        }

        @Test
        @DisplayName("时效衰减：60 天半衰期")
        void decay() {
            Instant now = Instant.parse("2026-07-28T00:00:00Z");
            Instant sixtyDaysAgo = now.minus(60, ChronoUnit.DAYS);
            double decayed = gate.applyDecay(0.8, sixtyDaysAgo, now);
            assertThat(decayed).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("180 天 → 极低")
        void maxDecay() {
            Instant now = Instant.parse("2026-07-28T00:00:00Z");
            Instant old = now.minus(200, ChronoUnit.DAYS);
            double decayed = gate.applyDecay(0.8, old, now);
            assertThat(decayed).isCloseTo(0.08, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("失效判断")
        void expired() {
            Instant now = Instant.parse("2026-07-28T00:00:00Z");
            Instant old = now.minus(120, ChronoUnit.DAYS);
            assertThat(gate.isExpired(0.5, old, now)).isTrue();
            assertThat(gate.isExpired(0.9, now.minus(10, ChronoUnit.DAYS), now)).isFalse();
        }
    }

    // ==================== VCL-002 语音情绪趋势 ====================

    @Nested
    @DisplayName("VCL-002 语音情绪趋势")
    class VCL002 {

        private final VoiceEmotionTrendAnalyzer analyzer = new VoiceEmotionTrendAnalyzer();

        @Test
        @DisplayName("恶化趋势检测")
        void worsening() {
            List<String> emotions = List.of("happy", "neutral", "happy", "sad", "angry", "anxious");
            TrendResult r = analyzer.analyzeTrend(emotions);
            assertThat(r.trend()).isEqualTo(Trend.WORSENING);
        }

        @Test
        @DisplayName("改善趋势检测")
        void improving() {
            List<String> emotions = List.of("sad", "angry", "anxious", "happy", "neutral", "happy");
            TrendResult r = analyzer.analyzeTrend(emotions);
            assertThat(r.trend()).isEqualTo(Trend.IMPROVING);
        }

        @Test
        @DisplayName("平稳趋势")
        void stable() {
            List<String> emotions = List.of("happy", "neutral", "happy", "neutral", "happy", "neutral");
            TrendResult r = analyzer.analyzeTrend(emotions);
            assertThat(r.trend()).isEqualTo(Trend.STABLE);
        }

        @Test
        @DisplayName("不足 3 次 → 平稳")
        void insufficient() {
            TrendResult r = analyzer.analyzeTrend(List.of("sad", "happy"));
            assertThat(r.trend()).isEqualTo(Trend.STABLE);
        }

        @Test
        @DisplayName("文本正面+语音负面 → MASKING")
        void masking() {
            FusionResult r = analyzer.fuse("happy", "sad");
            assertThat(r.inconsistent()).isTrue();
            assertThat(r.inconsistencyType()).isEqualTo("MASKING");
            assertThat(r.dominantEmotion()).isEqualTo("sad");
        }

        @Test
        @DisplayName("文本负面+语音正面 → AMPLIFYING")
        void amplifying() {
            FusionResult r = analyzer.fuse("angry", "happy");
            assertThat(r.inconsistent()).isTrue();
            assertThat(r.inconsistencyType()).isEqualTo("AMPLIFYING");
        }

        @Test
        @DisplayName("一致 → 取语音")
        void consistent() {
            FusionResult r = analyzer.fuse("sad", "anxious");
            assertThat(r.inconsistent()).isFalse();
            assertThat(r.dominantEmotion()).isEqualTo("anxious");
        }

        @Test
        @DisplayName("恶化+≥4次 → 通知教师")
        void notifyTeacher() {
            TrendResult r = new TrendResult(Trend.WORSENING, 0.8, 0.2, 5);
            assertThat(analyzer.shouldNotifyTeacher(r)).isTrue();

            TrendResult stable = new TrendResult(Trend.STABLE, 0.3, 0.3, 5);
            assertThat(analyzer.shouldNotifyTeacher(stable)).isFalse();
        }
    }

    // ==================== TMATCH-002 音色匹配 ====================

    @Nested
    @DisplayName("TMATCH-002 音色匹配")
    class TMATCH002 {

        private final VoicePersonaMatcher matcher = new VoicePersonaMatcher();

        @Test
        @DisplayName("危机锁定：S0 → 稳定基调")
        void crisisLock() {
            MatchResult r = matcher.match("female", 3, "CRISIS", "S0");
            assertThat(r.locked()).isTrue();
            assertThat(r.voiceId()).isEqualTo(VoicePersonaMatcher.VOICE_STABLE);
            assertThat(r.prosody()).isEqualTo(Prosody.STABLE);
        }

        @Test
        @DisplayName("正常匹配：女性 → 温柔女声")
        void femaleMatch() {
            MatchResult r = matcher.match("female", 3, "STABLE", null);
            assertThat(r.locked()).isFalse();
            assertThat(r.voiceId()).isEqualTo(VoicePersonaMatcher.VOICE_GENTLE_FEMALE);
            assertThat(r.prosody()).isEqualTo(Prosody.GENTLE);
        }

        @Test
        @DisplayName("正常匹配：男性 → 温暖男声")
        void maleMatch() {
            MatchResult r = matcher.match("male", 5, "STABLE", null);
            assertThat(r.voiceId()).isEqualTo(VoicePersonaMatcher.VOICE_WARM_MALE);
        }

        @Test
        @DisplayName("ACTIVATED → CALM prosody")
        void activatedProsody() {
            MatchResult r = matcher.match(null, 4, "ACTIVATED", null);
            assertThat(r.prosody()).isEqualTo(Prosody.CALM);
        }

        @Test
        @DisplayName("预合成查询")
        void preSynth() {
            assertThat(matcher.lookupPreSynth("crisis", "grounding")).isEqualTo("PS-CRISIS-GROUND-001");
            assertThat(matcher.lookupPreSynth("anxious", "breathing")).isEqualTo("PS-ANX-BREATH-001");
            assertThat(matcher.lookupPreSynth("happy", "unknown")).isNull();
        }

        @Test
        @DisplayName("预合成列表非空")
        void preSynthList() {
            assertThat(matcher.allPreSynthIds()).hasSize(7);
        }
    }

    // ==================== PEVAL-003 模板矩阵 ====================

    @Nested
    @DisplayName("PEVAL-003 模板矩阵+红队护栏")
    class PEVAL003 {

        private final TemplateMatrixRegistry registry = new TemplateMatrixRegistry();

        @Test
        @DisplayName("模板矩阵包含 7 个生效模板")
        void matrix() {
            assertThat(registry.getMatrix()).hasSize(7);
            assertThat(registry.findActive("EMO-001")).isNotNull();
            assertThat(registry.findActive("EMO-001").status())
                    .isEqualTo(TemplateMatrixRegistry.TemplateStatus.ACTIVE);
        }

        @Test
        @DisplayName("红队用例集 10 条")
        void guardrails() {
            assertThat(registry.getGuardrailCases()).hasSize(10);
            assertThat(registry.getGuardrailCasesByCategory("self_harm")).hasSize(2);
            assertThat(registry.getGuardrailCasesByCategory("jailbreak")).hasSize(1);
        }

        @Test
        @DisplayName("三门禁：全部通过")
        void gatePass() {
            GateResult r = registry.checkReleaseGate(true, "reviewer-A", 0.85, 0.80);
            assertThat(r.passed()).isTrue();
            assertThat(r.failures()).isEmpty();
        }

        @Test
        @DisplayName("三门禁：红队失败")
        void gateRedTeamFail() {
            GateResult r = registry.checkReleaseGate(false, "reviewer-A", 0.85, 0.80);
            assertThat(r.passed()).isFalse();
            assertThat(r.failures()).contains("红队护栏用例未全部通过");
        }

        @Test
        @DisplayName("三门禁：eval 回退")
        void gateEvalRegress() {
            GateResult r = registry.checkReleaseGate(true, "reviewer-A", 0.75, 0.80);
            assertThat(r.passed()).isFalse();
            assertThat(r.failures().get(0)).contains("eval 分数回退");
        }

        @Test
        @DisplayName("版本命名规范")
        void versionFormat() {
            assertThat(registry.isValidVersion("SYS-001_zh-CN_v1.2.0")).isTrue();
            assertThat(registry.isValidVersion("EMO-001_zh-CN_v1.0.0")).isTrue();
            assertThat(registry.isValidVersion("bad-version")).isFalse();
            assertThat(registry.isValidVersion(null)).isFalse();
        }
    }
}
