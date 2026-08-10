package com.mindsafe.domain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeviceCodeUtil 测试（CFG-005，doing/84 §5.2.1）
 * <p>
 * 覆盖：短码长度/字符集/确定性/校验位有效性/非法输入拒绝。
 */
class DeviceCodeUtilTest {

    @Test
    @DisplayName("生成的短码为 11 位且字符集合法")
    void generatedCodeHasValidShape() {
        String code = DeviceCodeUtil.generate("BB-2026-000123");
        assertThat(code).hasSize(DeviceCodeUtil.CODE_LENGTH);
        assertThat(code).matches("^[A-HJ-NP-Z2-9]{11}$");
    }

    @Test
    @DisplayName("同一 SN 生成确定性短码（幂等）")
    void generateIsDeterministic() {
        assertThat(DeviceCodeUtil.generate("BB-2026-000123"))
                .isEqualTo(DeviceCodeUtil.generate("BB-2026-000123"));
    }

    @Test
    @DisplayName("不同 SN 生成不同短码")
    void generateDiffersBySn() {
        assertThat(DeviceCodeUtil.generate("BB-2026-000123"))
                .isNotEqualTo(DeviceCodeUtil.generate("BB-2026-000456"));
    }

    @Test
    @DisplayName("生成的短码通过校验（含校验位）")
    void generatedCodePassesValidation() {
        String code = DeviceCodeUtil.generate("BB-2026-000123");
        assertThat(DeviceCodeUtil.isValid(code)).isTrue();
    }

    @Test
    @DisplayName("篡改内容位（不含校验位）校验失败")
    void tamperedPayloadFailsValidation() {
        String code = DeviceCodeUtil.generate("BB-2026-000123");
        char mutated = code.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = mutated + code.substring(1);
        assertThat(DeviceCodeUtil.isValid(tampered)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "K7M2P9XW4A",           // 长度不足
            "K7M2P9XW4AQQ",            // 长度超长
            "K7M2P9XW4AO",             // 含易混字符 O（非法）
            "k7m2p9xw4aq",             // 小写（非法）
            "K7M2P9XW4A1"              // 含数字 1（非法）
    })
    @DisplayName("非法短码（长度/字符集）校验拒绝")
    void invalidCodesRejected(String candidate) {
        assertThat(DeviceCodeUtil.isValid(candidate)).isFalse();
    }
}
