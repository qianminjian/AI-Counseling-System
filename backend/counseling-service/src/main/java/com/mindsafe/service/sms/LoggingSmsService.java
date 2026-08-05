package com.mindsafe.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 开发/试点环境短信服务实现（AUTH-013）
 * <p>
 * 不实际发送短信，仅将验证码输出到日志。
 * 激活条件：mindsafe.sms.provider=logging（默认）。
 * <p>
 * <b>修订（2026-08-06）：试点期 SMS 未启用，保留 LoggingSmsService 可在 prod profile 下加载，
 * 避免 PhoneVerificationService 注入失败卡住部署。</b>原 fix-smsprov 逻辑中
 * {@code @Profile("!prod")} 已移除；AUDIT-P0-5 修订为：
 * prod 环境必须由 docker-compose 显式设置 SMS_PROVIDER=aliyun 并补齐 4 个 aliyun 凭证后，
 * LoggingSmsService 才会被 AliyunSmsService 替代（{@code @ConditionalOnProperty} havingValue="aliyun"）。
 * 正式启用短信时审计需复核：必须确保 .env 同步设置 SMS_PROVIDER=aliyun。
 */
@Service
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
