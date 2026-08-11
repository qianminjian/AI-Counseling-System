package com.mindsafe.api.security;

/**
 * JWT tokenType 枚举（doing/92 R-016：原 5 处字符串字面量收敛单点——
 * 8/10 getTokenId 漏剥前缀事故同源模式：字面量未单点化即存在漂移面）。
 * <p>
 * claim 值保持兼容（存量 token 不受影响，只约束 Java 内部比较语义）。
 */
public enum TokenType {

    /** 业务 access token（2h，API 访问凭证） */
    ACCESS("access"),
    /** 业务 refresh token（7d，换发凭证） */
    REFRESH("refresh"),
    /** 平台管理员 token（PLATFORM_ 前缀 + 独立授权域，无 tenantId） */
    PLATFORM_ACCESS("platform_access"),
    /** 声纹设备凭证（7d，AUD-001） */
    VOICE_CREDENTIAL("voice_credential"),
    /** 家长报告链接 token（7d，SEC-006） */
    PARENT_REPORT("parent_report");

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    /** JWT claim 中的字符串值（与存量 token 载荷兼容） */
    public String claimValue() {
        return claimValue;
    }

    /** 按 claim 值反查枚举；未知值返回 null（调用方按非法类型拒绝，安全默认） */
    public static TokenType fromClaimValue(String value) {
        for (TokenType t : values()) {
            if (t.claimValue.equals(value)) {
                return t;
            }
        }
        return null;
    }
}
