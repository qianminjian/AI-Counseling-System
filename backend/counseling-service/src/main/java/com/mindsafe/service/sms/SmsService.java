package com.mindsafe.service.sms;

/**
 * 短信服务抽象接口（AUTH-013）
 * <p>
 * 可插拔实现：开发环境用 {@link LoggingSmsService}（仅日志），
 * 生产环境替换为阿里云 SMS / 腾讯云 SMS 实现。
 */
public interface SmsService {

    /**
     * 发送短信验证码。
     *
     * @param phone   目标手机号（已脱敏存储，此处为明文）
     * @param code    验证码（4-6 位数字）
     * @param purpose 用途描述（如 "家长身份验证"）
     * @return 是否发送成功
     */
    boolean sendVerificationCode(String phone, String code, String purpose);
}
