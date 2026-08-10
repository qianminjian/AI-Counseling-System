package com.mindsafe.service.toc;

import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocAuthService 测试（doing/85 TOC-001）
 * 覆盖：验证码发送（格式校验/60s 冷却/5 分钟 TTL）、注册（重复拒绝）、
 * 登录（未注册/已禁用拒绝）、验证码校验（错误/过期）。
 */
class TocAuthServiceTest {

    private TocFamilyAccountMapper accountMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TocAuthService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accountMapper = mock(TocFamilyAccountMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TocAuthService(accountMapper, redisTemplate);
    }

    @Test
    @DisplayName("sendCode：合法手机号生成 6 位验证码 + 写入 Redis 5 分钟 TTL")
    void sendCodeOk() {
        var result = service.sendCode("13800138000");
        assertThat(result.get("phone")).isEqualTo("138****8000");
        assertThat((String) result.get("code")).matches("^\\d{6}$");
        verify(valueOps).set(eq("toccode:13800138000"), any(String.class), any());
    }

    @Test
    @DisplayName("sendCode：非法手机号拒绝")
    void sendCodeInvalidPhone() {
        assertThatThrownBy(() -> service.sendCode("12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("手机号");
    }

    @Test
    @DisplayName("sendCode：60 秒冷却内拒绝重发")
    void sendCodeCooldown() {
        when(valueOps.get("toccode:13800138000:cd")).thenReturn("1720000000000");
        assertThatThrownBy(() -> service.sendCode("13800138000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("60 秒");
    }

    @Test
    @DisplayName("register：验证码正确 → 创建账号")
    void registerOk() {
        when(valueOps.get("toccode:13800138000")).thenReturn("123456");
        when(accountMapper.selectOne(any())).thenReturn(null);
        TocFamilyAccount account = service.register("13800138000", "123456");
        assertThat(account.getPhone()).isEqualTo("13800138000");
        assertThat(account.getStatus()).isEqualTo(TocFamilyAccount.STATUS_ACTIVE);
        verify(accountMapper).insert(any(TocFamilyAccount.class));
    }

    @Test
    @DisplayName("register：验证码错误拒绝")
    void registerWrongCode() {
        when(valueOps.get("toccode:13800138000")).thenReturn("654321");
        assertThatThrownBy(() -> service.register("13800138000", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码");
    }

    @Test
    @DisplayName("register：重复手机号拒绝")
    void registerDuplicate() {
        when(valueOps.get("toccode:13800138000")).thenReturn("123456");
        when(accountMapper.selectOne(any())).thenReturn(new TocFamilyAccount());
        assertThatThrownBy(() -> service.register("13800138000", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已注册");
    }

    @Test
    @DisplayName("login：验证码正确返回账号")
    void loginOk() {
        when(valueOps.get("toccode:13800138000")).thenReturn("123456");
        TocFamilyAccount existing = new TocFamilyAccount();
        existing.setPhone("13800138000");
        existing.setStatus(TocFamilyAccount.STATUS_ACTIVE);
        when(accountMapper.selectOne(any())).thenReturn(existing);
        assertThat(service.login("13800138000", "123456").getPhone()).isEqualTo("13800138000");
    }

    @Test
    @DisplayName("login：未注册账号拒绝")
    void loginNotFound() {
        when(valueOps.get("toccode:13800138000")).thenReturn("123456");
        when(accountMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.login("13800138000", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("先注册");
    }

    @Test
    @DisplayName("login：禁用账号拒绝")
    void loginDisabled() {
        when(valueOps.get("toccode:13800138000")).thenReturn("123456");
        TocFamilyAccount existing = new TocFamilyAccount();
        existing.setStatus(TocFamilyAccount.STATUS_DISABLED);
        when(accountMapper.selectOne(any())).thenReturn(existing);
        assertThatThrownBy(() -> service.login("13800138000", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁用");
    }
}
