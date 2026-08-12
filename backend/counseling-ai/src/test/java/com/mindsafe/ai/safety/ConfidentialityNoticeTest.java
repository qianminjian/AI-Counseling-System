package com.mindsafe.ai.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfidentialityNotice 单元测试（P1-4 板块02：SAFE-201 保密边界合规凭据锁定）。
 * <p>
 * 覆盖：两版话术三句核心承诺（陪伴/老师一般看不到/危险时告知大人）、
 * 分年级选版边界、话术不得含 PII 或模板占位符残留。
 */
class ConfidentialityNoticeTest {

    @Test
    @DisplayName("低年级版：三句核心承诺齐备（短句、儿童化）")
    void lowerGrade_threePromises() {
        assertThat(ConfidentialityNotice.NOTICE_LOWER_GRADE)
                .contains("我会一直陪你聊天")
                .contains("老师一般看不到")
                .contains("告诉能保护你的大人");
    }

    @Test
    @DisplayName("标准版：三句核心承诺齐备 + 非医生定位告知")
    void standard_threePromisesAndRoleNotice() {
        assertThat(ConfidentialityNotice.NOTICE_STANDARD)
                .contains("我会一直陪你聊天")
                .contains("老师一般看不到")
                .contains("告诉能保护你的大人")
                .contains("我不是医生");
    }

    @Test
    @DisplayName("forGrade：1-2 年级 → 柔和简化版；3-6 年级 → 完整版")
    void forGrade_selectsByGrade() {
        assertThat(ConfidentialityNotice.forGrade(1)).isEqualTo(ConfidentialityNotice.NOTICE_LOWER_GRADE);
        assertThat(ConfidentialityNotice.forGrade(2)).isEqualTo(ConfidentialityNotice.NOTICE_LOWER_GRADE);
        assertThat(ConfidentialityNotice.forGrade(3)).isEqualTo(ConfidentialityNotice.NOTICE_STANDARD);
        assertThat(ConfidentialityNotice.forGrade(6)).isEqualTo(ConfidentialityNotice.NOTICE_STANDARD);
    }

    @Test
    @DisplayName("两版话术均非空且不得含模板占位符残留（预审核直出）")
    void noPlaceholderResidue() {
        assertThat(ConfidentialityNotice.NOTICE_LOWER_GRADE).isNotBlank().doesNotContain("{");
        assertThat(ConfidentialityNotice.NOTICE_STANDARD).isNotBlank().doesNotContain("{");
    }

    @Test
    @DisplayName("不得含 PII 形态内容：无手机号/身份证/链接（合规凭据，改动即报警）")
    void noPiiLikeContent() {
        assertThat(ConfidentialityNotice.NOTICE_LOWER_GRADE)
                .doesNotContain("1")
                .doesNotContain("http");
        assertThat(ConfidentialityNotice.NOTICE_STANDARD)
                .doesNotContain("http")
                .doesNotContain("@");
    }
}
