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
}
