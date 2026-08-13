package com.mindsafe.service.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AliyunSmsService 阿里云短信单测：启动凭证校验 fail-fast + 发送成功/失败/异常路径
 * （HttpClient 经测试构造器注入 mock，不发起真实网络请求）。
 */
class AliyunSmsServiceTest {

    private AliyunSmsService service;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(HttpClient.class);
        service = new AliyunSmsService(new ObjectMapper(), httpClient);
        // 默认注入全部凭证，避免 validateCredentials 抛错
        Field[] fields = {
                AliyunSmsService.class.getDeclaredField("accessKeyId"),
                AliyunSmsService.class.getDeclaredField("accessKeySecret"),
                AliyunSmsService.class.getDeclaredField("signName"),
                AliyunSmsService.class.getDeclaredField("templateCode"),
        };
        String[] values = {"test-key-id", "test-key-secret", "测试签名", "SMS_123456"};
        for (int i = 0; i < fields.length; i++) {
            fields[i].setAccessible(true);
            fields[i].set(service, values[i]);
        }
    }

    private void stubSend(HttpResponse<String> resp) throws Exception {
        when(httpClient.send(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(resp);
    }

    @Test
    @DisplayName("凭证齐全：启动校验通过")
    void validateCredentials_ok() {
        service.validateCredentials(); // 不抛即通过
    }

    @Test
    @DisplayName("缺少 access-key-id → fail-fast")
    void validateCredentials_missingKey() throws Exception {
        Field f = AliyunSmsService.class.getDeclaredField("accessKeyId");
        f.setAccessible(true);
        f.set(service, null);

        assertThatThrownBy(() -> service.validateCredentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-key-id");
    }

    @Test
    @DisplayName("缺少 template-code → fail-fast")
    void validateCredentials_missingTemplate() throws Exception {
        Field f = AliyunSmsService.class.getDeclaredField("templateCode");
        f.setAccessible(true);
        f.set(service, "  ");

        assertThatThrownBy(() -> service.validateCredentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template-code");
    }

    @Test
    @DisplayName("发送成功：200 + Code=OK → true")
    void sendVerificationCode_success() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"Code\":\"OK\",\"Message\":\"success\"}");
        stubSend(resp);

        assertThat(service.sendVerificationCode("13800138000", "123456", "register")).isTrue();
    }

    @Test
    @DisplayName("发送失败：业务错误码 → false")
    void sendVerificationCode_bizFail() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"Code\":\"isv.MOBILE_NUMBER_ILLEGAL\"}");
        stubSend(resp);

        assertThat(service.sendVerificationCode("13800138000", "123456", "register")).isFalse();
    }

    @Test
    @DisplayName("HTTP 非 200 → false")
    void sendVerificationCode_httpError() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        stubSend(resp);

        assertThat(service.sendVerificationCode("13800138000", "123456", "register")).isFalse();
    }

    @Test
    @DisplayName("发送异常（网络）→ false 不抛出")
    void sendVerificationCode_exception() throws Exception {
        when(httpClient.send(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("timeout"));

        assertThat(service.sendVerificationCode("13800138000", "123456", "register")).isFalse();
    }
}
