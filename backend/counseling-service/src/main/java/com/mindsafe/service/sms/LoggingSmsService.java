package com.mindsafe.service.sms;

import jakarta.annotation.PostConstruct;
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
 * <p>
 * <b>BACK-002（doing/95）</b>：AliyunSmsService 侧已有凭证缺失 fail-fast（R-04），
 * 本实现补充启动期 ERROR 警示——provider=logging 时服务照常运行但验证码不会真实发送，
 * 防止生产部署在未启用短信的情况下静默运行（部署审计可据此检测）。
 */
@Service
@ConditionalOnProperty(name = "mindsafe.sms.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsService.class);

    /** BACK-002：启动警示——logging 模式下验证码不会真实发送（生产商用必须切 aliyun） */
    @PostConstruct
    void warnOnStartup() {
        log.error("[SMS-DEV-ONLY][启动警示] 当前短信提供商为 logging：验证码不会真实发送（仅写日志）。"
                + "生产商用前必须设置 MINDSAFE_SMS_PROVIDER=aliyun 并补齐 MINDSAFE_SMS_ALIYUN_* 四凭证"
                + "（凭证缺失时 AliyunSmsService fail-fast 启动失败，不会静默假发送）。");
    }

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
