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

    // ===== SAFE-204：姓名脱敏 =====

    @Nested
    @DisplayName("SAFE-204：姓名脱敏")
    class NameMasking {

        @Test
        @DisplayName("上下文句式「我叫+姓名」→ 某人")
        void should_mask_name_with_context() {
            assertThat(desensitizer.desensitize("我叫张小明"))
                    .isEqualTo("我叫某人");
        }

        @Test
        @DisplayName("上下文句式「同桌叫+姓名」→ 某人")
        void should_mask_classmate_name() {
            assertThat(desensitizer.desensitize("同桌叫李明"))
                    .isEqualTo("同桌叫某人");
        }

        @Test
        @DisplayName("上下文句式「老师叫+姓名」→ 某人")
        void should_mask_teacher_name() {
            assertThat(desensitizer.desensitize("老师叫王丽华"))
                    .isEqualTo("老师叫某人");
        }

        @Test
        @DisplayName("独立姓名（百家姓开头，标点边界）→ 某同学")
        void should_mask_standalone_name() {
            assertThat(desensitizer.desensitize("今天，陈明说了坏话。"))
                    .isEqualTo("今天，某同学说了坏话。");
        }

        @Test
        @DisplayName("不误伤常用两字词（非百家姓开头）")
        void should_not_mask_common_words() {
            // "今天" 不以百家姓开头 → 保留
            assertThat(desensitizer.desensitize("今天，很开心。"))
                    .isEqualTo("今天，很开心。");
        }

        @Test
        @DisplayName("不误伤没有标点边界的中文")
        void should_not_mask_without_boundary() {
            // "张明" 无标点边界 → 不触发独立姓名规则
            assertThat(desensitizer.desensitize("我和张明去玩了"))
                    .isEqualTo("我和张明去玩了");
        }
    }

    // ===== SAFE-204：地址脱敏 =====

    @Nested
    @DisplayName("SAFE-204：地址脱敏")
    class AddressMasking {

        @Test
        @DisplayName("完整地址（省市区路号）→ 某地")
        void should_mask_full_address() {
            String result = desensitizer.desensitize("我家在北京市朝阳区建国路88号");
            assertThat(result).contains("某地");
            assertThat(result).doesNotContain("朝阳区");
        }

        @Test
        @DisplayName("小区名 → 某地")
        void should_mask_community_name() {
            String result = desensitizer.desensitize("我住在阳光花园3栋");
            assertThat(result).contains("某地");
            assertThat(result).doesNotContain("阳光花园");
        }

        @Test
        @DisplayName("街道地址 + 门牌号 → 某地")
        void should_mask_street_address() {
            String result = desensitizer.desensitize("学校在中关村大街5号");
            assertThat(result).contains("某地");
            assertThat(result).doesNotContain("中关村大街");
        }

        @Test
        @DisplayName("不误伤普通文字")
        void should_not_mask_normal_chinese() {
            assertThat(desensitizer.desensitize("今天下午放学回家很开心"))
                    .isEqualTo("今天下午放学回家很开心");
        }
    }
}
