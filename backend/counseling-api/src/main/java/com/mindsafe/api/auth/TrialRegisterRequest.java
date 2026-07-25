package com.mindsafe.api.auth;

import jakarta.validation.constraints.*;

/**
 * 试用注册请求（POST /api/v1/auth/trial/register）
 */
public record TrialRegisterRequest(
        @NotBlank(message = "邀请码不能为空")
        String inviteCode,

        @NotBlank(message = "昵称不能为空")
        @Size(min = 2, max = 12, message = "昵称长度 2-12 字")
        String pseudonym,

        @Min(value = 6, message = "年龄不能小于 6")
        @Max(value = 120, message = "年龄不合法")
        int age,

        /** 体验者身份（家长/老师/其他），可选 */
        String role,

        @NotBlank(message = "同意版本号不能为空")
        String consentVersion,

        /** age<14 时必填（监护人手机号，脱敏存储） */
        String guardianPhone
) {
}
