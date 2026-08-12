package com.mindsafe.common.util;

/**
 * 手机号掩码工具（P2-1 收敛：AliyunSmsService / PhoneVerificationService / LoggingSmsService 的
 * 三处重复 maskPhone 统一到此单源）。
 * <p>
 * 规则：保留前 3 后 4，如 {@code 138****7890}；不足 7 位返回 {@code ***}（兜底防泄露）。
 * 仅用于日志脱敏展示，不改变任何业务语义。
 */
public final class PhoneMasker {

    private PhoneMasker() {
    }

    /**
     * 掩码手机号：保留前 3 后 4（138****7890）。
     *
     * @param phone 原始手机号
     * @return 掩码结果；null 或长度 &lt; 7 时返回 {@code ***}
     */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
