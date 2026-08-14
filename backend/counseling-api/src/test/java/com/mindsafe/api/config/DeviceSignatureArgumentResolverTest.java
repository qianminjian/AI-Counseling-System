package com.mindsafe.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.device.DeviceSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeviceSignatureArgumentResolver 单元测试（99-6，2026-08-14）
 */
class DeviceSignatureArgumentResolverTest {

    private DeviceSecurityService securityService;
    private DeviceSignatureArgumentResolver resolver;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        securityService = mock(DeviceSecurityService.class);
        objectMapper = new ObjectMapper();
        resolver = new DeviceSignatureArgumentResolver(securityService, objectMapper);
    }

    @Test
    @DisplayName("99-6：supportsParameter 仅接受带 @DeviceSignature 的参数")
    void supportsOnlyAnnotated() throws Exception {
        MethodParameter annotated = methodParam("annotated");
        MethodParameter plain = methodParam("plain");
        assertThat(resolver.supportsParameter(annotated)).isTrue();
        assertThat(resolver.supportsParameter(plain)).isFalse();
    }

    @Test
    @DisplayName("99-6：resolveArgument 用原始 body 校验签名并反序列化返回")
    void resolvesWithRawBody() throws Exception {
        String body = "{\"deviceCode\":\"K7M2P9XW4AQ\",\"firmwareVersion\":\"1.0\"}";
        HttpServletRequest request = mockRequest(body, "ts1", "nonce1", "sig1");
        when(securityService.enforceSignature("K7M2P9XW4AQ", body, "ts1", "nonce1", "sig1")).thenReturn(true);

        MethodParameter parameter = methodParam("annotated");
        DeviceCodeStub result = (DeviceCodeStub) resolver.resolveArgument(parameter, null,
                nativeRequest(request), null);

        assertThat(result.getDeviceCode()).isEqualTo("K7M2P9XW4AQ");
        assertThat(result.getFirmwareVersion()).isEqualTo("1.0");
        verify(securityService).enforceSignature("K7M2P9XW4AQ", body, "ts1", "nonce1", "sig1");
    }

    @Test
    @DisplayName("99-6：签名校验失败（抛 BizException）时参数不解析")
    void propagatesSignatureRejection() throws Exception {
        HttpServletRequest request = mockRequest("{\"deviceCode\":\"K7M2P9XW4AQ\"}", "ts1", "n1", "bad");
        when(securityService.enforceSignature(org.mockito.ArgumentMatchers.eq("K7M2P9XW4AQ"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new BizException(
                        com.mindsafe.common.dto.ErrorCode.UNAUTHORIZED, "设备请求签名校验失败"));

        assertThatThrownBy(() -> resolver.resolveArgument(methodParam("annotated"), null,
                nativeRequest(request), null)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("M1：空 body → BizException(400 语义) 而非 JsonParseException(500)")
    void emptyBodyIsParamInvalid() throws Exception {
        HttpServletRequest request = mockRequest("", "ts1", "n1", "sig1");
        assertThatThrownBy(() -> resolver.resolveArgument(methodParam("annotated"), null,
                nativeRequest(request), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请求体格式错误");
    }

    @Test
    @DisplayName("M1：畸形 JSON → BizException(400 语义)")
    void malformedBodyIsParamInvalid() throws Exception {
        HttpServletRequest request = mockRequest("{not-json", "ts1", "n1", "sig1");
        assertThatThrownBy(() -> resolver.resolveArgument(methodParam("annotated"), null,
                nativeRequest(request), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请求体格式错误");
    }

    // ===== fixtures =====

    private HttpServletRequest mockRequest(String body, String ts, String nonce, String sig) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getInputStream()).thenReturn(new jakarta.servlet.ServletInputStream() {
            private final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            private int index = 0;

            @Override public int read() { return index < bytes.length ? bytes[index++] & 0xFF : -1; }
            @Override public boolean isFinished() { return index >= bytes.length; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
        });
        when(request.getHeader("X-Device-Timestamp")).thenReturn(ts);
        when(request.getHeader("X-Device-Nonce")).thenReturn(nonce);
        when(request.getHeader("X-Device-Signature")).thenReturn(sig);
        return request;
    }

    private NativeWebRequest nativeRequest(HttpServletRequest request) {
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);
        return webRequest;
    }

    /** 测试用带注解/不带注解的方法参数 */
    private MethodParameter methodParam(String which) throws Exception {
        MethodParameter p = new MethodParameter(ControllerStub.class.getDeclaredMethod("handler", DeviceCodeStub.class), 0);
        if ("annotated".equals(which)) {
            return p;
        }
        // plain 参数：另一个无注解方法
        return new MethodParameter(ControllerStub.class.getDeclaredMethod("plain", DeviceCodeStub.class), 0);
    }

    static class ControllerStub {
        void handler(@DeviceSignature DeviceCodeStub body) {}
        void plain(DeviceCodeStub body) {}
    }

    static class DeviceCodeStub {
        private String deviceCode;
        private String firmwareVersion;

        public String getDeviceCode() { return deviceCode; }
        public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
        public String getFirmwareVersion() { return firmwareVersion; }
        public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    }
}
