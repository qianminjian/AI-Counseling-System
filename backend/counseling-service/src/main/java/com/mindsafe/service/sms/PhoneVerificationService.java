package com.mindsafe.service.sms;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * 手机验证码服务（AUTH-013）
 * <p>
 * 流程：生成 6 位验证码 → Redis 存储（5 分钟 TTL）→ 通过 SmsService 发送 → 校验。
 * 防刷：同一手机号 60 秒内不可重复发送；验证 5 次失败即废码（O3 防爆破）。
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

    private static final String CODE_KEY_PREFIX = "sms:code:";
    private static final String COOLDOWN_KEY_PREFIX = "sms:cooldown:";
    private static final int CODE_LENGTH = 6;
    private static final int CODE_TTL_MINUTES = 5;
    private static final int COOLDOWN_SECONDS = 60;
    private static final String ATTEMPT_KEY_PREFIX = "sms:attempt:";
    private static final int MAX_ATTEMPTS = 5;   // O3：5 次失败即废码，防暴力枚举

    private final StringRedisTemplate redisTemplate;
    private final SmsService smsService;
    private final SecureRandom random = new SecureRandom();

    public PhoneVerificationService(StringRedisTemplate redisTemplate, SmsService smsService) {
        this.redisTemplate = redisTemplate;
        this.smsService = smsService;
    }

    /**
     * 发送验证码到指定手机号。
     *
     * @param phone   手机号
     * @param purpose 用途（如 "家长身份验证"）
     */
    public void sendCode(String phone, String purpose) {
        validatePhone(phone);

        // 防刷：60 秒冷却
        String cooldownKey = COOLDOWN_KEY_PREFIX + phone;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "发送过于频繁，请 60 秒后重试");
        }

        // 生成验证码
        String code = generateCode();

        // 存储（5 分钟有效）+ 清除旧失败计数
        String codeKey = CODE_KEY_PREFIX + phone;
        String attemptKey = ATTEMPT_KEY_PREFIX + phone;
        redisTemplate.opsForValue().set(codeKey, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN_SECONDS, TimeUnit.SECONDS);
        redisTemplate.delete(attemptKey);

        // 发送
        boolean sent = smsService.sendVerificationCode(phone, code, purpose);
        if (!sent) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "短信发送失败，请稍后重试");
        }

        log.info("验证码已发送: phone={}, purpose={}", maskPhone(phone), purpose);
    }

    /**
     * 校验验证码（O3 防爆破：同一手机号最多 5 次失败尝试，超限即废码）。
     * 验证成功后立即删除 code + attempt（一次性，防重放）。
     *
     * @param phone 手机号
     * @param code  用户输入的验证码
     * @return 验证是否通过
     */
    public boolean verifyCode(String phone, String code) {
        if (phone == null || code == null) return false;

        String codeKey = CODE_KEY_PREFIX + phone;
        String attemptKey = ATTEMPT_KEY_PREFIX + phone;

        // O3：失败计数检查
        String attemptVal = redisTemplate.opsForValue().get(attemptKey);
        int attempts = attemptVal != null ? Integer.parseInt(attemptVal) : 0;
        if (attempts >= MAX_ATTEMPTS) {
            // 超限废码：删除验证码，确保攻击者无法继续尝试
            redisTemplate.delete(codeKey);
            redisTemplate.delete(attemptKey);
            log.warn("验证码暴力尝试锁定: phone={}, attempts={}", maskPhone(phone), attempts);
            return false;
        }

        String stored = redisTemplate.opsForValue().get(codeKey);

        if (stored == null) {
            return false; // 过期或未发送
        }
        if (!stored.equals(code.trim())) {
            // O3：失败计数 +1，TTL 对齐验证码有效期（5 分钟）
            redisTemplate.opsForValue().set(attemptKey, String.valueOf(attempts + 1),
                    CODE_TTL_MINUTES, TimeUnit.MINUTES);
            return false;
        }

        // 验证成功，立即删除 code + attempt（防重放）
        redisTemplate.delete(codeKey);
        redisTemplate.delete(attemptKey);
        return true;
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        int num = random.nextInt(bound);
        return String.format("%0" + CODE_LENGTH + "d", num);
    }

    private void validatePhone(String phone) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "手机号格式不正确");
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
