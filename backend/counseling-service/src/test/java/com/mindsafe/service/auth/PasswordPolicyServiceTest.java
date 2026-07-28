package com.mindsafe.service.auth;

import com.mindsafe.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyServiceTest {

    private final PasswordPolicyService service = new PasswordPolicyService(8, 90);

    // ===== 复杂度校验 =====

    @Test
    void 合法密码通过校验() {
        assertDoesNotThrow(() -> service.validateComplexity("abc12345"));
    }

    @Test
    void null密码抛异常() {
        BizException ex = assertThrows(BizException.class, () -> service.validateComplexity(null));
        assertTrue(ex.getMessage().contains("至少"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab1", "abc1234", "1234567"})
    void 长度不足抛异常(String password) {
        assertThrows(BizException.class, () -> service.validateComplexity(password));
    }

    @Test
    void 纯字母无数字抛异常() {
        BizException ex = assertThrows(BizException.class, () -> service.validateComplexity("abcdefgh"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void 纯数字无字母抛异常() {
        BizException ex = assertThrows(BizException.class, () -> service.validateComplexity("12345678"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void 特殊字符加字母数字通过() {
        assertDoesNotThrow(() -> service.validateComplexity("P@ss1234"));
    }

    // ===== 过期判断 =====

    @Test
    void 从未设置密码视为过期() {
        assertTrue(service.isExpired(null));
    }

    @Test
    void 刚改密码未过期() {
        assertFalse(service.isExpired(Instant.now()));
    }

    @Test
    void 超过90天已过期() {
        Instant old = Instant.now().minus(91, ChronoUnit.DAYS);
        assertTrue(service.isExpired(old));
    }

    @Test
    void 恰好90天未过期() {
        Instant boundary = Instant.now().minus(89, ChronoUnit.DAYS);
        assertFalse(service.isExpired(boundary));
    }

    @Test
    void maxAgeDays为0时永不过期() {
        PasswordPolicyService noExpiry = new PasswordPolicyService(8, 0);
        assertFalse(noExpiry.isExpired(null));
        assertFalse(noExpiry.isExpired(Instant.now().minus(365, ChronoUnit.DAYS)));
    }

    @Test
    void getMaxAgeDays返回配置值() {
        assertEquals(90, service.getMaxAgeDays());
    }
}
