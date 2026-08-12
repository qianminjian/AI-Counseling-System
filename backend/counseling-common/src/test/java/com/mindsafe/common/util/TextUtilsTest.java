package com.mindsafe.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextUtils（BA-15：LLM 输出代码围栏剥离）单元测试
 * <p>
 * 覆盖：纯 JSON 直通、```json 围栏剥离、无结尾围栏、单行围栏、空白/null 防护。
 */
class TextUtilsTest {

    @Test
    @DisplayName("纯 JSON（无围栏）原样返回（trim 后）")
    void stripCodeFence_plainJson() {
        String raw = "  {\"decision\":\"pass\"}  ";
        assertThat(TextUtils.stripCodeFence(raw)).isEqualTo("{\"decision\":\"pass\"}");
    }

    @Test
    @DisplayName("```json 围栏包裹时剥离首尾围栏")
    void stripCodeFence_fencedJson() {
        String raw = "```json\n{\"decision\":\"block\"}\n```";
        assertThat(TextUtils.stripCodeFence(raw)).isEqualTo("{\"decision\":\"block\"}");
    }

    @Test
    @DisplayName("仅开头有围栏（无结尾）时剥离开头并保留内容")
    void stripCodeFence_missingClosingFence() {
        String raw = "```\n{\"decision\":\"rewrite\"}";
        assertThat(TextUtils.stripCodeFence(raw)).isEqualTo("{\"decision\":\"rewrite\"}");
    }

    @Test
    @DisplayName("单行围栏（```json 无换行）不剥离，原样返回")
    void stripCodeFence_singleLineFence() {
        String raw = "```json";
        assertThat(TextUtils.stripCodeFence(raw)).isEqualTo("```json");
    }

    @Test
    @DisplayName("空白输入返回空串，null 输入返回 null")
    void stripCodeFence_blankAndNull() {
        assertThat(TextUtils.stripCodeFence("   \n\t ")).isEmpty();
        assertThat(TextUtils.stripCodeFence(null)).isNull();
    }

    // ===== truncateByCodePoint（P1-6 / R-021：code point 截断，不劈开 emoji 代理对） =====

    @Test
    @DisplayName("null 输入返回 null；短文本原样返回")
    void truncateByCodePoint_nullAndShort() {
        assertThat(TextUtils.truncateByCodePoint(null, 10)).isNull();
        assertThat(TextUtils.truncateByCodePoint("你好世界", 10)).isEqualTo("你好世界");
    }

    @Test
    @DisplayName("超长含 emoji：截断到 maxLen 个 code point，不劈开代理对（UTF-16 substring 会切出孤立代理项）")
    void truncateByCodePoint_emojiSurrogatePairPreserved() {
        // 1023 个 BMP 字符 + 1 个 emoji（2 个 UTF-16 code unit）→ 1024 code points / 1025 UTF-16 units
        String emoji = "\uD83D\uDE00"; // 😀
        String text = "a".repeat(1023) + emoji;
        assertThat(text.length()).isEqualTo(1025);
        assertThat(text.codePointCount(0, text.length())).isEqualTo(1024);

        String result = TextUtils.truncateByCodePoint(text, 1024);
        // code point 口径：1024 个 code point 全部保留（含完整 emoji）；旧 substring(0,1024) 会劈开 emoji
        assertThat(result).isEqualTo(text);
        assertThat(result.codePointCount(0, result.length())).isEqualTo(1024);
        // 无孤立代理项（合法 Unicode 串，可安全进入字段加密）
        assertThat(result).matches(s -> s.codePoints().noneMatch(cp -> Character.isSurrogate((char) cp)));
    }

    @Test
    @DisplayName("超长截断：严格按 code point 边界截断")
    void truncateByCodePoint_cutsAtCodePointBoundary() {
        String emoji = "\uD83D\uDE00"; // 😀
        String text = "abcd" + emoji + "efg"; // 5 code points / 7 UTF-16 units
        // 截断到 4 个 code point：'abcd'（emoji 不进入，因第 5 个 code point 才轮到它）
        assertThat(TextUtils.truncateByCodePoint(text, 4)).isEqualTo("abcd");
        // 截断到 5 个 code point：'abcd' + 完整 emoji
        assertThat(TextUtils.truncateByCodePoint(text, 5)).isEqualTo("abcd" + emoji);
    }
}
