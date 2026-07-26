package com.mindsafe.service.auth;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 密码策略服务（AUTH-014）
 * <p>
 * 适用对象：正式账号（教师 / 管理员）。试用学生走 PIN 码登录，不受密码策略约束。
 * <ul>
 *   <li>复杂度：≥ {@code min-length} 位，且同时包含字母与数字</li>
 *   <li>过期：超过 {@code max-age-days} 天未改密视为过期（0 或负数表示永不过期）</li>
 * </ul>
 * 参数可通过 {@code mindsafe.security.password-policy.*} 配置覆盖。
 */
@Service
public class PasswordPolicyService {

    private final int minLength;
    private final int maxAgeDays;

    public PasswordPolicyService(
            @Value("${mindsafe.security.password-policy.min-length:8}") int minLength,
            @Value("${mindsafe.security.password-policy.max-age-days:90}") int maxAgeDays) {
        this.minLength = minLength;
        this.maxAgeDays = maxAgeDays;
    }

    /**
     * 校验密码复杂度，不满足则抛出 {@link ErrorCode#PASSWORD_POLICY_VIOLATION}。
     */
    public void validateComplexity(String password) {
        if (password == null || password.length() < minLength) {
            throw new BizException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码至少 " + minLength + " 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码需同时包含字母和数字");
        }
    }

    /**
     * 判断密码是否已过期。
     *
     * @param passwordChangedAt 最近一次改密时间；{@code null} 视为从未设置（判为过期，强制首次设置）
     * @return true 表示已过期，需要强制改密
     */
    public boolean isExpired(Instant passwordChangedAt) {
        if (maxAgeDays <= 0) {
            return false; // 管理员关闭过期策略
        }
        if (passwordChangedAt == null) {
            return true;
        }
        return passwordChangedAt.plus(maxAgeDays, ChronoUnit.DAYS).isBefore(Instant.now());
    }

    public int getMaxAgeDays() {
        return maxAgeDays;
    }
}
