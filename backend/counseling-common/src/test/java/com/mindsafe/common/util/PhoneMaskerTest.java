package com.mindsafe.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PhoneMasker 单测（P2-1：sms 三处 maskPhone 收敛单源后，语义回归保障）。
 */
class PhoneMaskerTest {

    @Test
    @DisplayName("标准 11 位手机号：保留前 3 后 4")
    void mask_standard() {
        assertThat(PhoneMasker.mask("13812347890")).isEqualTo("138****7890");
    }

    @Test
    @DisplayName("长度 < 7：兜底返回 ***")
    void mask_short() {
        assertThat(PhoneMasker.mask("13812")).isEqualTo("***");
    }

    @Test
    @DisplayName("null：返回 ***")
    void mask_null() {
        assertThat(PhoneMasker.mask(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("7 位边界：保留前 3 后 4")
    void mask_boundary7() {
        assertThat(PhoneMasker.mask("1381234")).isEqualTo("138****1234");
    }
}
