package com.mindsafe.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HighSensitivityCategories 单元测试（DC-001，doing/72 §16）
 * <p>
 * 覆盖：SAFE-202 高敏门控委托语义——中文权威类别命中、旧英文类别零残留。
 */
class HighSensitivityCategoriesTest {

    @Test
    @DisplayName("中文权威高敏类别 → 命中（SAFE-202 门控接线）")
    void chineseCategoryHits() {
        assertThat(HighSensitivityCategories.isHighSensitivity("性侵/性骚扰")).isTrue();
        assertThat(HighSensitivityCategories.isHighSensitivity("自伤/自杀")).isTrue();
        assertThat(HighSensitivityCategories.isHighSensitivity("家庭虐待/忽视")).isTrue();
        assertThat(HighSensitivityCategories.isHighSensitivity("他伤/暴力")).isTrue();
        assertThat(HighSensitivityCategories.isHighSensitivity("严重抑郁/绝望")).isTrue();
    }

    @Test
    @DisplayName("非高敏类别 → 不命中")
    void nonHighSensitivityMisses() {
        assertThat(HighSensitivityCategories.isHighSensitivity("霸凌/网络欺凌")).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("躯体化/进食睡眠")).isFalse();
    }

    @Test
    @DisplayName("旧英文类别常量零残留（physical_abuse 等不再识别）")
    void legacyEnglishCategoriesRejected() {
        assertThat(HighSensitivityCategories.isHighSensitivity("physical_abuse")).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("sexual_abuse")).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("domestic_violence")).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("self_harm")).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("suicidal_ideation")).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("bereavement")).isFalse();
    }

    @Test
    @DisplayName("null/空白 → 不命中")
    void nullAndBlankMiss() {
        assertThat(HighSensitivityCategories.isHighSensitivity(null)).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("")).isFalse();
        assertThat(HighSensitivityCategories.isHighSensitivity("  ")).isFalse();
    }
}
