package com.mindsafe.service.billing;

import com.mindsafe.service.billing.EntitlementChecker.CheckResult;
import com.mindsafe.service.billing.EntitlementChecker.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EntitlementChecker 测试（板块03 P1-6 补测：计费决策表零回归保护）
 * <p>
 * 覆盖：4 计划 × 7 功能权益决策表全覆盖、豁免清单硬编码路径无条件放行、
 * 未知计划安全默认（无权益）、前缀漂移边界（/api/v1/alert 不命中 /api/v1/alerts）。
 */
class EntitlementCheckerTest {

    private final EntitlementChecker checker = new EntitlementChecker();

    @ParameterizedTest(name = "{0} × {1} → allowed={2}")
    @CsvSource({
            // plan, feature, expected
            "TRIAL, ai_chat, true",
            "TRIAL, tts, true",
            "TRIAL, assessment, false",
            "TRIAL, export, false",
            "TRIAL, parent_h5, false",
            "TRIAL, voice_input, false",
            "TRIAL, data_dashboard, false",
            "BASIC, ai_chat, true",
            "BASIC, tts, true",
            "BASIC, parent_h5, true",
            "BASIC, assessment, false",
            "BASIC, export, false",
            "BASIC, voice_input, false",
            "BASIC, data_dashboard, false",
            "STANDARD, ai_chat, true",
            "STANDARD, tts, true",
            "STANDARD, parent_h5, true",
            "STANDARD, assessment, true",
            "STANDARD, export, true",
            "STANDARD, voice_input, true",
            "STANDARD, data_dashboard, false",
            "PREMIUM, ai_chat, true",
            "PREMIUM, tts, true",
            "PREMIUM, parent_h5, true",
            "PREMIUM, assessment, true",
            "PREMIUM, export, true",
            "PREMIUM, voice_input, true",
            "PREMIUM, data_dashboard, true"
    })
    void decisionMatrix(Plan plan, String feature, boolean expected) {
        CheckResult result = checker.checkFeature(plan, feature, "/api/v1/session");
        assertThat(result.allowed()).isEqualTo(expected);
        if (!expected) {
            assertThat(result.httpStatus()).isEqualTo(403);
            assertThat(result.code()).isEqualTo("30002");
        } else {
            assertThat(result.httpStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("豁免清单硬编码：预警/SOS/危机/教师预警处理路径无条件放行（即使权益缺失）")
    void exemptPathsAlwaysPass() {
        // TRIAL 无 export/data_dashboard 权益，但豁免路径直接放行
        assertThat(checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_EXPORT,
                "/api/v1/alerts/123").allowed()).isTrue();
        assertThat(checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_DATA_DASHBOARD,
                "/api/v1/sos/page").allowed()).isTrue();
        assertThat(checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_DATA_DASHBOARD,
                "/api/v1/crisis/hotline").allowed()).isTrue();
        assertThat(checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_EXPORT,
                "/api/v1/teacher/alerts/claim").allowed()).isTrue();
        // 非豁免路径同功能应拒绝（证明豁免清单是唯一放行原因）
        assertThat(checker.checkFeature(Plan.TRIAL, EntitlementChecker.FEAT_EXPORT,
                "/api/v1/session").allowed()).isFalse();
    }

    @Test
    @DisplayName("豁免前缀边界：/api/v1/alert 不命中 /api/v1/alerts，防前缀漂移")
    void exemptPrefixBoundary() {
        assertThat(checker.isExempt("/api/v1/alert")).isFalse();
        assertThat(checker.isExempt("/api/v1/so")).isFalse();
        assertThat(checker.isExempt("/api/v1/teacher/alert")).isFalse();
        assertThat(checker.isExempt(null)).isFalse();
        assertThat(checker.isExempt("")).isFalse();
    }

    @Test
    @DisplayName("未知计划安全默认：不返回任何功能（无权益 → 403）")
    void unknownPlanDeniesAll() {
        CheckResult result = checker.checkFeature(null, EntitlementChecker.FEAT_AI_CHAT, "/api/v1/session");
        assertThat(result.allowed()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(403);
    }
}
