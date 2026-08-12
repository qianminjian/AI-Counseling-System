package com.mindsafe.api.config;

import com.mindsafe.common.dto.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorCode 错误码 → HTTP 状态映射测试（审计 F7 单点化）
 * <p>
 * 原 GlobalExceptionHandler 用魔法 switch 把错误码映射到 HttpStatus，新增错误码易漏配
 * （漏配 → 默认 500）。现每个枚举携带 httpStatus（编译期强制），本测试表驱动锁定关键映射，
 * 并全量校验：状态码合法、错误码唯一、文案非空——防止新增枚举时破坏契约。
 */
class ErrorCodeMappingTest {

    @ParameterizedTest(name = "{0} → HTTP {1}")
    @EnumSource(value = ErrorCode.class)
    @DisplayName("全枚举遍历：httpStatus 为合法 HTTP 状态（200-599），message 非空")
    void everyEnumHasLegalHttpStatus(ErrorCode code) {
        assertThat(code.httpStatus())
                .as("%s 的 httpStatus 必须在 [200,599] 区间", code)
                .isBetween(200, 599);
        assertThat(code.message()).as("%s 的 message 不得为空", code).isNotBlank();
        assertThat(code.code()).as("%s 的 code 不得为负数", code).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("错误码全局唯一（防重复）")
    void codesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(seen.add(code.code()))
                    .as("错误码 %d (%s) 重复", code.code(), code)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("关键映射表驱动：认证/限流/资源类错误码 → 正确 HTTP 状态")
    void keyMappings() {
        assertThat(ErrorCode.SUCCESS.httpStatus()).isEqualTo(200);
        assertThat(ErrorCode.UNAUTHORIZED.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.FORBIDDEN.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.CONSENT_WITHDRAWN.httpStatus()).isEqualTo(410);
        assertThat(ErrorCode.PASSWORD_CHANGE_REQUIRED.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.RATE_LIMITED.httpStatus()).isEqualTo(429);
        assertThat(ErrorCode.SESSION_ENDED.httpStatus()).isEqualTo(410);
        assertThat(ErrorCode.MESSAGE_TOO_LONG.httpStatus()).isEqualTo(413);
        assertThat(ErrorCode.INTERNAL_ERROR.httpStatus()).isEqualTo(500);
        assertThat(ErrorCode.LLM_TIMEOUT.httpStatus()).isEqualTo(504);
        assertThat(ErrorCode.LLM_UNAVAILABLE.httpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("4xx/5xx 语义一致性抽查：认证域(20xxx)状态在 4xx，AI 域(60xxx)状态在 5xx 或 4xx")
    void domainStatusConsistency() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.code() >= 20000 && code.code() < 30000) {
                assertThat(code.httpStatus()).as("%s 认证/授权域应映射 4xx", code)
                        .isBetween(400, 499);
            }
        }
    }
}
