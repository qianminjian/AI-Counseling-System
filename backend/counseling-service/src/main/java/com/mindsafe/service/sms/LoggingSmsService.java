package com.mindsafe.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 开发/测试环境短信服务实现（AUTH-013）
 * <p>
 * 不实际发送短信，仅将验证码输出到日志。
 * 激活条件：mindsafe.sms.provider=logging（默认）
 */
@Service
@ConditionalOnProperty(name = "mindsafe.sms.provider", havingValue = "logging", matchIfMissing = true)
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
