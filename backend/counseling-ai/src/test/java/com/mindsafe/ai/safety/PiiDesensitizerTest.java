package com.mindsafe.ai.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PiiDesensitizer 单元测试
 * <p>
 * 覆盖：手机号/身份证/邮箱掩码、混合 PII、普通文本零误伤。
 */
class PiiDesensitizerTest {

    private PiiDesensitizer desensitizer;

    @BeforeEach
    void setUp() {
        desensitizer = new PiiDesensitizer();
    }

    @Nested
    @DisplayName("手机号脱敏")
    class PhoneMasking {

        @Test
        @DisplayName("标准手机号 → 前3后2掩码")
        void should_mask_phone() {
            assertThat(desensitizer.desensitize("我的电话是13812345678"))
                    .isEqualTo("我的电话是138****78");
        }

        @Test
        @DisplayName("句中手机号（含标点边界）")
        void should_mask_phone_in_sentence() {
            assertThat(desensitizer.desensitize("打给我，15987654321，好吗？"))
                    .isEqualTo("打给我，159****21，好吗？");
        }

        @Test
        @DisplayName("多个手机号全部掩码")
        void should_mask_multiple_phones() {
            String result = desensitizer.desensitize("爸爸13812345678妈妈13998765432");
            assertThat(result).isEqualTo("爸爸138****78妈妈139****32");
        }
    }

    @Nested
    @DisplayName("身份证号脱敏")
    class IdCardMasking {

        @Test
        @DisplayName("18 位身份证（数字结尾）→ 前3后2掩码")
        void should_mask_id_card() {
            assertThat(desensitizer.desensitize("身份证号110101201001011234"))
                    .isEqualTo("身份证号110*************34");
        }

        @Test
        @DisplayName("18 位身份证（X 结尾）")
        void should_mask_id_card_with_x() {
            assertThat(desensitizer.desensitize("11010120100101123X"))
                    .isEqualTo("110*************3X");
        }
    }

    @Nested
    @DisplayName("邮箱脱敏")
    class EmailMasking {

        @Test
        @DisplayName("标准邮箱 → 本地名首字符掩码")
        void should_mask_email() {
            assertThat(desensitizer.desensitize("我的邮箱是test@example.com"))
                    .isEqualTo("我的邮箱是t***@example.com");
        }

        @Test
        @DisplayName("单字符本地名邮箱")
        void should_mask_single_char_local_email() {
            assertThat(desensitizer.desensitize("a@qq.com")).isEqualTo("a@qq.com");
        }
    }

    @Nested
    @DisplayName("混合与边界")
    class MixedAndBoundary {

        @Test
        @DisplayName("同句含手机+邮箱 → 全部脱敏")
        void should_mask_mixed_pii() {
            String result = desensitizer.desensitize("电话13812345678，邮箱tom@qq.com");
            assertThat(result).isEqualTo("电话138****78，邮箱t***@qq.com");
        }

        @Test
        @DisplayName("null/空串原样返回")
        void should_return_null_and_empty_as_is() {
            assertThat(desensitizer.desensitize(null)).isNull();
            assertThat(desensitizer.desensitize("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("零误伤（普通文本不变）")
    class NoFalsePositive {

        @ParameterizedTest
        @ValueSource(strings = {
                "我今天很开心",
                "今天考了100分",
                "20240101是元旦",
                "我家有12345本书",
                "我10岁了，上四年级"
        })
        @DisplayName("普通数字/文本 → 不被误伤")
        void should_not_mask_normal_text(String text) {
            assertThat(desensitizer.desensitize(text)).isEqualTo(text);
        }

        @Test
        @DisplayName("10 位数字（非手机号长度）不误伤")
        void should_not_mask_ten_digits() {
            assertThat(desensitizer.desensitize("号码1381234567")).isEqualTo("号码1381234567");
        }

        @Test
        @DisplayName("12 位连续数字（非手机/身份证）不误伤")
        void should_not_mask_twelve_digits() {
            assertThat(desensitizer.desensitize("123456789012")).isEqualTo("123456789012");
        }
    }
}
