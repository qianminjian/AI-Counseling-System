package com.mindsafe.service.device;

import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.entity.DeviceBindCode;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.mapper.DeviceBindCodeMapper;
import com.mindsafe.domain.mapper.DeviceBindingMapper;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.domain.util.DeviceCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeviceService 测试（CFG-001/004，doing/84 §六）
 * <p>
 * 覆盖：首次上线自动注册、心跳、脱敏查询、在线判定（90s 阈值）、
 * 验证码会话（生成/校验/3 次锁定）、绑定/解绑状态机、配置拉取。
 */
class DeviceServiceTest {

    private DeviceMapper deviceMapper;
    private DeviceBindingMapper bindingMapper;
    private DeviceBindCodeMapper bindCodeMapper;
    private DeviceService service;

    private final String deviceCode = DeviceCodeUtil.generate("BB-2026-000123");
    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        bindingMapper = mock(DeviceBindingMapper.class);
        bindCodeMapper = mock(DeviceBindCodeMapper.class);
        service = new DeviceService(deviceMapper, bindingMapper, bindCodeMapper);
    }

    private Device unboundDevice() {
        Device d = new Device();
        d.setDeviceId(deviceId);
        d.setDeviceCode(deviceCode);
        d.setSn("BB-2026-000123");
        d.setDeviceType("desk_toy");
        d.setStatus(Device.STATUS_ONLINE_UNBOUND);
        d.setLastOnlineAt(Instant.now());
        return d;
    }

    // ===== reportOnline（AC-84-24 自动注册） =====

    @Test
    @DisplayName("首次上线自动注册：不存在则创建并置 ONLINE_UNBOUND")
    void reportOnlineRegistersNewDevice() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(deviceMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = service.reportOnline(deviceCode, "BB-2026-000123", "v0.1.0", "https://mindsafe.local");

        assertThat(result.get("status")).isEqualTo(Device.STATUS_ONLINE_UNBOUND);
        verify(deviceMapper).insert(any(Device.class));
    }

    @Test
    @DisplayName("已绑定设备回连上报保持 ONLINE_BOUND")
    void reportOnlineKeepsBoundStatus() {
        Device bound = unboundDevice();
        bound.setStatus(Device.STATUS_ONLINE_BOUND);
        when(deviceMapper.selectOne(any())).thenReturn(bound);

        Map<String, Object> result = service.reportOnline(deviceCode, "BB-2026-000123", "v0.1.0", null);

        assertThat(result.get("status")).isEqualTo(Device.STATUS_ONLINE_BOUND);
        verify(deviceMapper, never()).insert(any(Device.class));
    }

    @Test
    @DisplayName("非法设备码上报被拒绝")
    void reportOnlineRejectsInvalidCode() {
        assertThatThrownBy(() -> service.reportOnline("BADCODE", "SN1", "v1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("设备码不合法");
    }

    @Test
    @DisplayName("SN 被占用时上报被拒绝（防串码）")
    void reportOnlineRejectsDuplicateSn() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(deviceMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.reportOnline(deviceCode, "OTHER-SN", "v1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SN 已被其他设备码占用");
    }

    // ===== 心跳与在线判定 =====

    @Test
    @DisplayName("心跳更新最近在线时间")
    void heartbeatUpdatesLastOnlineAt() {
        when(deviceMapper.selectOne(any())).thenReturn(unboundDevice());
        service.heartbeat(deviceCode);
        verify(deviceMapper).updateById(any(Device.class));
    }

    @Test
    @DisplayName("90s 内有心跳判定在线")
    void statusIsOnlineWithinHeartbeatWindow() {
        Device d = unboundDevice();
        d.setLastOnlineAt(Instant.now().minusSeconds(30));
        when(deviceMapper.selectOne(any())).thenReturn(d);

        Map<String, Object> status = service.getDeviceStatus(deviceCode);

        assertThat(status.get("online")).isEqualTo(true);
    }

    @Test
    @DisplayName("超 90s 无心跳判定离线")
    void statusIsOfflineAfterTimeout() {
        Device d = unboundDevice();
        d.setLastOnlineAt(Instant.now().minusSeconds(120));
        when(deviceMapper.selectOne(any())).thenReturn(d);

        Map<String, Object> status = service.getDeviceStatus(deviceCode);

        assertThat(status.get("online")).isEqualTo(false);
    }

    // ===== 脱敏查询 =====

    @Test
    @DisplayName("info 仅返回脱敏信息，不泄露 SN")
    void infoIsMasked() {
        when(deviceMapper.selectOne(any())).thenReturn(unboundDevice());
        Map<String, Object> info = service.getDeviceInfo(deviceCode);
        assertThat(info.get("codeTail")).isEqualTo(deviceCode.substring(deviceCode.length() - 4));
        assertThat(info).doesNotContainKey("sn");
    }

    // ===== 绑定验证码（AC-84-23/10/11/12） =====

    @Test
    @DisplayName("生成验证码：返回 6 位明文，库中存哈希")
    void createBindCodeReturnsPlainAndStoresHash() {
        when(deviceMapper.selectOne(any())).thenReturn(unboundDevice());
        when(bindingMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = service.createBindCode(deviceCode, "teacher-1");

        String plain = (String) result.get("code");
        assertThat(plain).matches("^\\d{6}$");
        assertThat(result.get("expiresAt")).isNotNull();
        verify(bindCodeMapper).insert(any(DeviceBindCode.class));
    }

    @Test
    @DisplayName("已绑定设备不能生成绑定码")
    void createBindCodeRejectedWhenBound() {
        Device bound = unboundDevice();
        bound.setStatus(Device.STATUS_ONLINE_BOUND);
        when(deviceMapper.selectOne(any())).thenReturn(bound);

        assertThatThrownBy(() -> service.createBindCode(deviceCode, "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已绑定");
    }

    @Test
    @DisplayName("绑定成功：建立 ACTIVE 绑定 + 设备 ONLINE_BOUND + 验证码作废")
    void bindSuccess() {
        Device d = unboundDevice();
        when(deviceMapper.selectOne(any())).thenReturn(d);
        when(bindingMapper.selectCount(any())).thenReturn(0L);

        DeviceBindCode code = new DeviceBindCode();
        code.setCodeId(UUID.randomUUID());
        code.setDeviceId(deviceId);
        code.setCodeHash(sha256("123456"));
        code.setExpiresAt(Instant.now().plusSeconds(300));
        code.setFailCount(0);
        when(bindCodeMapper.selectOne(any())).thenReturn(code);

        Map<String, Object> result = service.bind(deviceCode, DeviceBinding.BIND_TYPE_CLASS,
                UUID.randomUUID(), null, "123456", "teacher-1");

        assertThat(result.get("status")).isEqualTo(Device.STATUS_ONLINE_BOUND);
        verify(bindingMapper).insert(any(DeviceBinding.class));
        verify(bindCodeMapper).updateById(any(DeviceBindCode.class)); // used_at 置位
        verify(deviceMapper).updateById(any(Device.class));           // ONLINE_BOUND
    }

    @Test
    @DisplayName("验证码错误 3 次锁定 5 分钟")
    void bindLocksAfterThreeFailures() {
        Device d = unboundDevice();
        when(deviceMapper.selectOne(any())).thenReturn(d);
        when(bindingMapper.selectCount(any())).thenReturn(0L);

        DeviceBindCode code = new DeviceBindCode();
        code.setCodeId(UUID.randomUUID());
        code.setDeviceId(deviceId);
        code.setCodeHash(sha256("123456"));
        code.setExpiresAt(Instant.now().plusSeconds(300));
        code.setFailCount(2);
        when(bindCodeMapper.selectOne(any())).thenReturn(code);

        assertThatThrownBy(() -> service.bind(deviceCode, DeviceBinding.BIND_TYPE_CLASS,
                UUID.randomUUID(), null, "999999", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已锁定");

        verify(bindCodeMapper).updateById(any(DeviceBindCode.class)); // fail_count=3 + locked_until
        verify(bindingMapper, never()).insert(any(DeviceBinding.class));
    }

    @Test
    @DisplayName("验证码过期拒绝绑定")
    void bindRejectsExpiredCode() {
        Device d = unboundDevice();
        when(deviceMapper.selectOne(any())).thenReturn(d);
        when(bindingMapper.selectCount(any())).thenReturn(0L);

        DeviceBindCode code = new DeviceBindCode();
        code.setCodeId(UUID.randomUUID());
        code.setDeviceId(deviceId);
        code.setCodeHash(sha256("123456"));
        code.setExpiresAt(Instant.now().minusSeconds(10));
        code.setFailCount(0);
        when(bindCodeMapper.selectOne(any())).thenReturn(code);

        assertThatThrownBy(() -> service.bind(deviceCode, DeviceBinding.BIND_TYPE_CLASS,
                UUID.randomUUID(), null, "123456", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过期");
    }

    // ===== 解绑 =====

    @Test
    @DisplayName("解绑：绑定置 UNBOUND，设备回 ONLINE_UNBOUND")
    void unbindFlowsBackToUnbound() {
        Device bound = unboundDevice();
        bound.setStatus(Device.STATUS_ONLINE_BOUND);
        when(deviceMapper.selectOne(any())).thenReturn(bound);

        DeviceBinding active = new DeviceBinding();
        active.setBindingId(UUID.randomUUID());
        active.setStatus(DeviceBinding.STATUS_ACTIVE);
        when(bindingMapper.selectList(any())).thenReturn(List.of(active));

        Map<String, Object> result = service.unbind(deviceCode, "teacher-1");

        assertThat(result.get("status")).isEqualTo(Device.STATUS_ONLINE_UNBOUND);
        verify(bindingMapper).updateById(any(DeviceBinding.class));
        verify(deviceMapper).updateById(any(Device.class));
    }

    // ===== 配置拉取 =====

    @Test
    @DisplayName("配置拉取返回服务器地址")
    void pullConfigReturnsServerUrl() {
        Device d = unboundDevice();
        d.setServerUrl("https://mindsafe.school.local");
        when(deviceMapper.selectOne(any())).thenReturn(d);

        Map<String, Object> config = service.pullConfig(deviceCode);

        assertThat(config.get("serverUrl")).isEqualTo("https://mindsafe.school.local");
        assertThat(config.get("heartbeatIntervalSeconds")).isEqualTo(30L);
    }

    // ===== CFG-008 M13：设备操作（ota/reboot/factory-reset） =====

    @Test
    @DisplayName("ota：受理并登记操作意图")
    void otaAccepted() {
        when(deviceMapper.selectOne(any())).thenReturn(unboundDevice());
        Map<String, Object> result = service.ota(deviceCode, "admin-1");
        assertThat(result.get("action")).isEqualTo("ota");
        assertThat(result.get("operator")).isEqualTo("admin-1");
    }

    @Test
    @DisplayName("reboot：受理")
    void rebootAccepted() {
        when(deviceMapper.selectOne(any())).thenReturn(unboundDevice());
        Map<String, Object> result = service.reboot(deviceCode, "admin-1");
        assertThat(result.get("action")).isEqualTo("reboot");
    }

    @Test
    @DisplayName("factoryReset：解绑全部绑定 + 状态回 UNACTIVATED")
    void factoryResetUnbindsAndResets() {
        Device bound = unboundDevice();
        bound.setStatus(Device.STATUS_ONLINE_BOUND);
        bound.setServerUrl("https://mindsafe.school.local");
        when(deviceMapper.selectOne(any())).thenReturn(bound);

        DeviceBinding active = new DeviceBinding();
        active.setBindingId(UUID.randomUUID());
        active.setStatus(DeviceBinding.STATUS_ACTIVE);
        when(bindingMapper.selectList(any())).thenReturn(List.of(active));

        Map<String, Object> result = service.factoryReset(deviceCode, "admin-1");

        assertThat(result.get("status")).isEqualTo(Device.STATUS_UNACTIVATED);
        assertThat(result.get("unboundCount")).isEqualTo(1);
        verify(bindingMapper).updateById(any(DeviceBinding.class));
        verify(deviceMapper).updateById(any(Device.class));
    }

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
