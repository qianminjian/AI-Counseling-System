package com.mindsafe.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 开发/测试环境短信服务实现（AUTH-013）
 * <p>
 * 不实际发送短信，仅将验证码输出到日志。
 * 激活条件：mindsafe.sms.provider=logging（默认）+ 非 prod profile。
 * <p>
 * <b>fix-smsprov：生产环境（prod profile）禁止加载此实现</b>，
 * 强制运维在 .env 中设置 SMS_PROVIDER=aliyun 并使用 AliyunSmsService，
 * 避免生产环境因配置遗漏导致家长验证码静默未发送。
 */
@Service
@Profile("!prod")
@ConditionalOnProperty(name = "mindsafe.sms.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsService.class);

    @Override
    public boolean sendVerificationCode(String phone, String code, String purpose) {
        // 脱敏手机号用于日志（138****7890）
        String masked = maskPhone(phone);
        // AUDIT-P0-5：醒目标记，防止生产日志被误读为真实短信已发送
        log.info("[SMS-DEV-ONLY][生产环境禁用-验证码未真实发送] phone={} | code={} | purpose={}", masked, code, purpose);
        return true;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
