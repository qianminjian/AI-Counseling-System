package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.TocAuthProvider;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.service.toc.TocAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocAuthController 测试（doing/85 TOC-001）
 * 覆盖：send-code/register/login 错误映射（400）、token 签发（Controller 层）、me。
 */
class TocAuthControllerTest {

    private TocAuthService tocAuthService;
    private TocAuthProvider tocAuthProvider;
    private TocAuthController controller;

    @BeforeEach
    void setUp() {
        tocAuthService = mock(TocAuthService.class);
        tocAuthProvider = mock(TocAuthProvider.class);
        controller = new TocAuthController(tocAuthService, tocAuthProvider);
    }

    @Test
    @DisplayName("sendCode：非法手机号转 400")
    void sendCodeError() {
        when(tocAuthService.sendCode("12345")).thenThrow(new IllegalArgumentException("手机号格式非法"));
        var response = controller.sendCode(Map.of("phone", "12345"));
        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("手机号");
    }

    @Test
    @DisplayName("register：成功 → 签发 token（userType=toc_parent，tenantId=null）")
    void registerOk() {
        TocFamilyAccount account = new TocFamilyAccount();
        account.setFamilyAccountId(UUID.randomUUID());
        account.setPhone("13800138000");
        when(tocAuthService.register("13800138000", "123456")).thenReturn(account);
        Map<String, Object> session = new java.util.LinkedHashMap<>();
        session.put("token", "jwt-token");
        when(tocAuthProvider.buildSession(account)).thenReturn(session);

        var response = controller.register(Map.of("phone", "13800138000", "code", "123456"));

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("token")).isEqualTo("jwt-token");
        verify(tocAuthProvider).buildSession(account);
    }

    @Test
    @DisplayName("login：账号不存在转 400")
    void loginError() {
        when(tocAuthService.login("13800138000", "123456"))
                .thenThrow(new IllegalArgumentException("账号不存在，请先注册"));
        var response = controller.login(Map.of("phone", "13800138000", "code", "123456"));
        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("先注册");
    }

    @Test
    @DisplayName("me：从 TenantContext 取账号返回脱敏信息")
    void meOk() {
        UUID accountId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(null, accountId, "toc_parent"));
        TocFamilyAccount account = new TocFamilyAccount();
        account.setFamilyAccountId(accountId);
        account.setPhone("13800138000");
        account.setStatus(TocFamilyAccount.STATUS_ACTIVE);
        when(tocAuthService.getById(accountId)).thenReturn(account);

        var response = controller.me(auth);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("phone")).isEqualTo("138****8000");
    }
}
