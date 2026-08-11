package com.mindsafe.service.device;

import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.mapper.DeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    // ===== AUDIT-DEEP-001（2026-08-11）：过期校验 + 签名防篡改 =====

    @Test
    @DisplayName("新格式 token：签发后立即校验通过（code+exp+sig 全匹配）")
    void validateTokenNewFormatOk() {
        Device device = new Device();
        device.setDeviceId(java.util.UUID.randomUUID());
        device.setDeviceCode(deviceCode);
        when(deviceMapper.updateById(any(Device.class))).thenReturn(1);
        var creds = service.issueCredentials(device);

        // 从 updateById 捕获落库的 secret（issueCredentials 内部 update 对象）
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper).updateById(captor.capture());
        String storedSecret = captor.getValue().getDeviceSecret();

        // 回读落库的 secret/token 后校验
        when(deviceMapper.selectOne(any())).thenAnswer(inv -> {
            Device d = new Device();
            d.setDeviceCode(deviceCode);
            d.setDeviceSecret(storedSecret);
            d.setDeviceToken(creds.token());
            return d;
        });
        assertThat(service.validateToken(creds.token(), deviceCode)).isTrue();
    }

    @Test
    @DisplayName("过期 token（exp 已过）→ false（AUDIT-DEEP-001 过期窗口）")
    void validateTokenExpired() {
        Device device = new Device();
        device.setDeviceCode(deviceCode);
        String secret = DeviceSecurityService.generateSecret();
        long expired = System.currentTimeMillis() - 1000; // 1 秒前过期
        String token = "DVC_" + deviceCode + "_" + expired + "_"
                + DeviceSecurityService.sign(deviceCode + "|" + expired, secret);
        device.setDeviceSecret(secret);
        device.setDeviceToken(token);
        when(deviceMapper.selectOne(any())).thenReturn(device);

        assertThat(service.validateToken(token, deviceCode)).isFalse();
    }

    @Test
    @DisplayName("篡改签名 → false（防篡改：签名重算比对）")
    void validateTokenTampered() {
        Device device = new Device();
        device.setDeviceCode(deviceCode);
        String secret = DeviceSecurityService.generateSecret();
        long exp = System.currentTimeMillis() + 60_000;
        String token = "DVC_" + deviceCode + "_" + exp + "_"
                + DeviceSecurityService.sign(deviceCode + "|" + exp, secret);
        device.setDeviceSecret(secret);
        device.setDeviceToken(token);
        when(deviceMapper.selectOne(any())).thenReturn(device);

        String tampered = "DVC_" + deviceCode + "_" + exp + "_" + "0".repeat(64);
        assertThat(service.validateToken(tampered, deviceCode)).isFalse();
    }

    @Test
    @DisplayName("旧 3 段格式 token → 等值比较回退兼容（P3-4，已上线设备命中分支）")
    void validateTokenLegacyThreePart() {
        Device device = new Device();
        device.setDeviceCode(deviceCode);
        String legacyToken = "DVC_" + deviceCode + "_" + DeviceSecurityService.sign("s", "s");
        device.setDeviceToken(legacyToken);
        when(deviceMapper.selectOne(any())).thenReturn(device);

        assertThat(service.validateToken(legacyToken, deviceCode)).isTrue();
        // 不同 token 等值比较失败
        assertThat(service.validateToken("DVC_" + deviceCode + "_other", deviceCode)).isFalse();
    }

    @Test
    @DisplayName("deviceCode 不匹配 → false（防跨设备 token 复用）")
    void validateTokenWrongDevice() {
        Device device = new Device();
        device.setDeviceCode(deviceCode);
        String secret = DeviceSecurityService.generateSecret();
        long exp = System.currentTimeMillis() + 60_000;
        String token = "DVC_" + deviceCode + "_" + exp + "_"
                + DeviceSecurityService.sign(deviceCode + "|" + exp, secret);
        device.setDeviceSecret(secret);
        device.setDeviceToken(token);
        when(deviceMapper.selectOne(any())).thenReturn(device);

        assertThat(service.validateToken(token, "OTHER_CODE00")).isFalse();
    }
}
