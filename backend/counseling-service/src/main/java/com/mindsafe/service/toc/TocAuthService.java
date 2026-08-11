package com.mindsafe.service.toc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import com.mindsafe.service.auth.LoginRateLimiter;
import com.mindsafe.service.security.FieldEncryptionService;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * toC 家庭账号服务（doing/85 TOC-001，toC-AC-1）
 * <p>
 * 手机号验证码注册/登录，独立于校园账号体系（与 ParentAccount/family_code 解耦）。
 * 验证码：Redis 5 分钟 TTL（前缀 toccode:），60s 重发限制；登录成功后由
 * Controller 层签发 JWT（userType=toc_parent，tenantId=null 平台级）。
 */
@Service
public class TocAuthService implements EnvironmentAware, LoginRateLimiter {

    /** 验证码 Redis key 前缀 */
    private static final String CODE_KEY_PREFIX = "toccode:";

    /** 验证码有效期 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    /** 重发冷却 */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** 登录/注册速率限制（P1） */
    private static final String RATE_KEY_PREFIX = "ratelimit:toc:auth:";
    private static final int RATE_MAX_ATTEMPTS = 10;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private Environment environment;

    private final TocFamilyAccountMapper accountMapper;
    private final StringRedisTemplate redisTemplate;
    private final FieldEncryptionService encryptionService;

    public TocAuthService(TocFamilyAccountMapper accountMapper, StringRedisTemplate redisTemplate,
                          FieldEncryptionService encryptionService) {
        this.accountMapper = accountMapper;
        this.redisTemplate = redisTemplate;
        this.encryptionService = encryptionService;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * 发送验证码（注册/登录共用）：60s 重发冷却 + 5 分钟有效。
     * 验证码明文仅 dev/test profile 回显（P0-3：生产接入短信通道后无回显）。
     */
    public Map<String, Object> sendCode(String phone) {
        rateLimitCheck(phone);
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw new IllegalArgumentException("手机号格式非法");
        }
        String cooldownKey = CODE_KEY_PREFIX + phone + ":cd";
        String lastSent = redisTemplate.opsForValue().get(cooldownKey);
        if (lastSent != null) {
            throw new IllegalArgumentException("发送过于频繁，请 60 秒后再试");
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + phone, code, CODE_TTL);
        redisTemplate.opsForValue().set(cooldownKey, String.valueOf(System.currentTimeMillis()), RESEND_COOLDOWN);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phone", maskPhone(phone));
        result.put("expiresInSeconds", CODE_TTL.toSeconds());
        // P0-3：验证码明文仅 dev/test profile 回显（生产接入短信通道后无回显）
        if (environment != null && !environment.acceptsProfiles("prod")) {
            result.put("code", code);
        }
        return result;
    }

    /**
     * 注册：手机号 + 验证码 → 创建家庭账号，返回账号信息（token 由 Controller 签发）。
     * P0-4：手机号经 FieldEncryptionService 加密后存储（ENCRYPTION_ENABLED=false 时明文透传）。
     */
    public TocFamilyAccount register(String phone, String code) {
        rateLimitCheck(phone);
        verifyCode(phone, code);
        String encryptedPhone = encryptionService.encrypt(phone);
        TocFamilyAccount existing = accountMapper.selectOne(
                new LambdaQueryWrapper<TocFamilyAccount>().eq(TocFamilyAccount::getPhone, encryptedPhone));
        if (existing != null) {
            throw new IllegalArgumentException("该手机号已注册，请直接登录");
        }
        TocFamilyAccount account = new TocFamilyAccount();
        account.setFamilyAccountId(UUID.randomUUID());
        account.setPhone(encryptedPhone);
        account.setStatus(TocFamilyAccount.STATUS_ACTIVE);
        Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);
        return account;
    }

    /**
     * 登录：手机号 + 验证码（已注册账号），返回账号信息（token 由 Controller 签发）。
     * P0-4：查询前对输入手机号执行与存储时相同的 encrypt 操作匹配。
     */
    public TocFamilyAccount login(String phone, String code) {
        rateLimitCheck(phone);
        verifyCode(phone, code);
        TocFamilyAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<TocFamilyAccount>().eq(TocFamilyAccount::getPhone, encryptionService.encrypt(phone)));
        if (account == null) {
            throw new IllegalArgumentException("账号不存在，请先注册");
        }
        if (!TocFamilyAccount.STATUS_ACTIVE.equals(account.getStatus())) {
            throw new IllegalArgumentException("账号已禁用");
        }
        return account;
    }

    /** 按账号 ID 查询（me/隔离校验用）。 */
    public TocFamilyAccount getById(UUID familyAccountId) {
        return accountMapper.selectById(familyAccountId);
    }

    private void verifyCode(String phone, String code) {
        if (code == null || !code.matches("^\\d{6}$")) {
            throw new IllegalArgumentException("验证码为 6 位数字");
        }
        String expected = redisTemplate.opsForValue().get(CODE_KEY_PREFIX + phone);
        if (expected == null || !expected.equals(code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        redisTemplate.delete(CODE_KEY_PREFIX + phone);
    }

    private static String maskPhone(String phone) {
        return phone == null || phone.length() < 7 ? phone
                : phone.substring(0, 3) + "****" + phone.substring(7);
    }

    // ===== LoginRateLimiter 适配（doing/89 N-001） =====

    @Override
    public void checkLockout(String identifier) {
        rateLimitCheck(identifier);
    }

    @Override
    public void recordFailure(String identifier) {
        // 频率模型不按失败计数锁定（每分钟 10 次已覆盖），无操作
    }

    @Override
    public void clearFailures(String identifier) {
        // 频率窗口自动过期，无操作
    }

    /** P1 速率限制：同一手机号每分钟最多 10 次认证请求 */
    private void rateLimitCheck(String phone) {
        String key = RATE_KEY_PREFIX + phone;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, RATE_WINDOW.toSeconds(), TimeUnit.SECONDS);
        }
        if (count != null && count > RATE_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("操作过于频繁，请稍后再试");
        }
    }
}
