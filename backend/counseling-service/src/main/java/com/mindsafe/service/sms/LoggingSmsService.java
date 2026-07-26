package com.mindsafe.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 开发/测试环境短信服务实现（AUTH-013）
 * <p>
 * 不实际发送短信，仅将验证码输出到日志。
 * 生产环境需替换为阿里云/腾讯云 SMS 实现（实现 {@link SmsService} 接口，
 * 通过 {@code @Primary} 或 Profile 切换）。
 */
@Service
public class LoggingSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsService.class);

    @Override
    public boolean sendVerificationCode(String phone, String code, String purpose) {
        // 脱敏手机号用于日志（138****7890）
        String masked = maskPhone(phone);
        log.info("[SMS-DEV] 验证码发送 | phone={} | code={} | purpose={}", masked, code, purpose);
        return true;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
