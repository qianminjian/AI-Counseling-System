package com.mindsafe.service.sms;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PhoneVerificationService 发送时序单测（P2-3：先发短信成功再写 Redis，失败不占冷却键）。
 * <p>
 * 契约：
 * - 短信发送失败 → 抛 INTERNAL_ERROR，且不写 codeKey/cooldownKey/attemptKey（消除“冷却占位假死窗口”）
 * - 短信发送成功 → 先 sendVerificationCode 后写 Redis，验证码 TTL/冷却时长与语义不变
 * - 冷却期内 → 拒绝发送
 */
@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SmsService smsService;
    @Mock private ValueOperations<String, String> valueOps;

    private PhoneVerificationService service;

    @BeforeEach
    void setUp() {
        // lenient：冷却拒绝/非法手机号用例不触及 opsForValue（strict 模式显式声明，同 TeacherStatsPerformanceTest 先例）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new PhoneVerificationService(redisTemplate, smsService);
    }

    @Test
    @DisplayName("发送失败：抛异常且不写任何 Redis 键（不占冷却键）")
    void sendCode_failure_doesNotWriteRedis() {
        when(smsService.sendVerificationCode(eq("13812347890"), anyString(), eq("家长身份验证")))
                .thenReturn(false);

        assertThatThrownBy(() -> service.sendCode("13812347890", "家长身份验证"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.code());

        verify(valueOps, never()).set(anyString(), anyString(), eq(5L), eq(TimeUnit.MINUTES));
        verify(valueOps, never()).set(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("发送成功：先发短信后写 Redis，冷却/验证码 TTL 不变")
    void sendCode_success_sendBeforeWrite() {
        when(smsService.sendVerificationCode(eq("13812347890"), anyString(), eq("家长身份验证")))
                .thenReturn(true);

        service.sendCode("13812347890", "家长身份验证");

        InOrder inOrder = inOrder(smsService, valueOps, redisTemplate);
        inOrder.verify(smsService).sendVerificationCode(eq("13812347890"), anyString(), eq("家长身份验证"));
        inOrder.verify(valueOps).set(eq("sms:code:13812347890"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
        inOrder.verify(valueOps).set(eq("sms:cooldown:13812347890"), eq("1"), eq(60L), eq(TimeUnit.SECONDS));
        inOrder.verify(redisTemplate).delete("sms:attempt:13812347890");
    }

    @Test
    @DisplayName("冷却期内：拒绝发送（60 秒冷却语义不变）")
    void sendCode_inCooldown_rejected() {
        when(redisTemplate.hasKey("sms:cooldown:13812347890")).thenReturn(true);

        assertThatThrownBy(() -> service.sendCode("13812347890", "家长身份验证"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        verify(smsService, never()).sendVerificationCode(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("手机号非法：拒绝发送")
    void sendCode_invalidPhone_rejected() {
        assertThatThrownBy(() -> service.sendCode("12345", "家长身份验证"))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        verify(smsService, never()).sendVerificationCode(anyString(), anyString(), anyString());
    }
}
