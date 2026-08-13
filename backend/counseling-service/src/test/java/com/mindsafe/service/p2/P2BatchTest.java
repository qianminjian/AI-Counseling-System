package com.mindsafe.service.p2;

import com.mindsafe.service.billing.EntitlementChecker;
import com.mindsafe.service.billing.EntitlementChecker.CheckResult;
import com.mindsafe.service.billing.EntitlementChecker.Plan;
import com.mindsafe.service.profile.ProfileMergeGate;
import com.mindsafe.service.profile.ProfileMergeGate.MergeDecision;
import com.mindsafe.service.prompt.TemplateMatrixRegistry;
import com.mindsafe.service.prompt.TemplateMatrixRegistry.GateResult;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer.FusionResult;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer.Trend;
import com.mindsafe.service.voice.VoiceEmotionTrendAnalyzer.TrendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 批次全量测试：BILL-001/002 + PROF-023 + VCL-002 + TMATCH-002 + PEVAL-003
 */
class P2BatchTest {

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
            CheckResult r = checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_EXPORT, "/api/v1/sos/call");
            assertThat(r.allowed()).isTrue();
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

    // ==================== PEVAL-003 模板矩阵 ====================

    @Nested
    @DisplayName("PEVAL-003 模板矩阵+红队护栏")
    class PEVAL003 {

        private final TemplateMatrixRegistry registry = new TemplateMatrixRegistry();

        @Test
        @DisplayName("模板矩阵包含 14 个生效模板（与 design/18 §0 + prompts/ 文件对齐）")
        void matrix() {
            assertThat(registry.getMatrix()).hasSize(14);
            assertThat(registry.findActive("EMO_001")).isNotNull();
            assertThat(registry.findActive("EMO_001").status())
                    .isEqualTo(TemplateMatrixRegistry.TemplateStatus.ACTIVE);
            assertThat(registry.findActive("SAF_002")).isNotNull();
            assertThat(registry.findActive("LANG_001").audience()).isEqualTo("grade_1_2");
            assertThat(registry.findActive("SKL_001")).isNotNull();
        }

        @Test
        @DisplayName("红队用例集 14 条（G-1 扩至 6 类攻击面）")
        void guardrails() {
            assertThat(registry.getGuardrailCases()).hasSize(14);
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
        @DisplayName("版本命名规范（ARCH-010 D4：下划线 key 单一源）")
        void versionFormat() {
            assertThat(registry.isValidVersion("SYS_001_zh-CN_v1.2.0")).isTrue();
            assertThat(registry.isValidVersion("EMO_001_zh-CN_v1.0.0")).isTrue();
            assertThat(registry.isValidVersion("bad-version")).isFalse();
            assertThat(registry.isValidVersion(null)).isFalse();
        }
    }
}
