package com.mindsafe.service.device;

import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.mapper.DeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceSecurityServiceTest {

    private DeviceMapper deviceMapper;
    private DeviceSecurityService service;

    private final String deviceCode = "K7M2P9XW4AQ";

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        service = new DeviceSecurityService(deviceMapper);
    }

    @Test
    @DisplayName("issueCredentials：生成 64 字符 secret + DVC_ 前缀 token")
    void issueCredentialsOk() {
        Device device = new Device();
        device.setDeviceId(java.util.UUID.randomUUID());
        device.setDeviceCode(deviceCode);
        when(deviceMapper.updateById(any(Device.class))).thenReturn(1);

        var creds = service.issueCredentials(device);
        assertThat(creds.token()).startsWith("DVC_" + deviceCode + "_");
        assertThat(creds.token().length()).isGreaterThan(20);
        assertThat(creds.expiresAt()).isPositive();
    }

    @Test
    @DisplayName("validateToken：合法 token 返回 true")
    void validateTokenOk() {
        Device device = new Device();
        device.setDeviceCode(deviceCode);
        String expectedToken = "DVC_" + deviceCode + "_" + DeviceSecurityService.sign("s", "s");
        device.setDeviceToken(expectedToken);
        when(deviceMapper.selectOne(any())).thenReturn(device);

        assertThat(service.validateToken(expectedToken, deviceCode)).isTrue();
    }

    @Test
    @DisplayName("validateToken：无效格式返回 false")
    void validateTokenInvalidFormat() {
        assertThat(service.validateToken("BAD_TOKEN", deviceCode)).isFalse();
        assertThat(service.validateToken(null, deviceCode)).isFalse();
    }

    @Test
    @DisplayName("generateSecret：返回 64 字符 hex（32 字节）")
    void generateSecretLength() {
        String secret = DeviceSecurityService.generateSecret();
        assertThat(secret).hasSize(64); // 32 bytes = 64 hex chars
    }

    @Test
    @DisplayName("sign：HMAC-SHA256 返回 64 字符 hex")
    void signReturns64Hex() {
        String sig = DeviceSecurityService.sign("test-data", "test-key");
        assertThat(sig).hasSize(64);
    }
}
