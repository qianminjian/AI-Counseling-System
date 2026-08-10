package com.mindsafe.service.device;

import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.entity.DeviceQrIssuance;
import com.mindsafe.domain.mapper.DeviceBindingMapper;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.domain.mapper.DeviceQrIssuanceMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlatformDeviceService 测试（CFG-008 admin-web M13，doing/84 §六.2）
 * <p>
 * 覆盖：跨租户列表（状态筛选/归属过滤/在线判定）、详情（绑定历史）、
 * 二维码批量签发（校验/落库/未找到）、批量操作（受理/非法操作拒绝）。
 */
class PlatformDeviceServiceTest {

    private DeviceMapper deviceMapper;
    private DeviceBindingMapper bindingMapper;
    private DeviceQrIssuanceMapper qrIssuanceMapper;
    private PlatformDeviceService service;

    private final String deviceCode = DeviceCodeUtil.generate("BB-2026-000123");
    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        bindingMapper = mock(DeviceBindingMapper.class);
        qrIssuanceMapper = mock(DeviceQrIssuanceMapper.class);
        service = new PlatformDeviceService(deviceMapper, bindingMapper, qrIssuanceMapper);
    }

    private Device sampleDevice() {
        Device d = new Device();
        d.setDeviceId(deviceId);
        d.setDeviceCode(deviceCode);
        d.setSn("BB-2026-000123");
        d.setDeviceType("desk_toy");
        d.setFirmwareVersion("v0.1.0");
        d.setStatus(Device.STATUS_ONLINE_BOUND);
        d.setLastOnlineAt(Instant.now());
        return d;
    }

    @Test
    @DisplayName("跨租户列表：返回设备 + 在线判定 + 绑定摘要")
    void listDevicesOk() {
        Device d = sampleDevice();
        when(deviceMapper.selectList(any())).thenReturn(List.of(d));
        when(bindingMapper.selectOne(any())).thenReturn(null);

        List<Map<String, Object>> devices = service.listDevices(null, null);

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).get("deviceCode")).isEqualTo(deviceCode);
        assertThat(devices.get(0).get("online")).isEqualTo(true);
        assertThat(devices.get(0).get("binding")).isNull();
    }

    @Test
    @DisplayName("跨租户列表：归属过滤（bindTargetId 不匹配的设备被过滤）")
    void listDevicesFiltersByBinding() {
        Device d = sampleDevice();
        when(deviceMapper.selectList(any())).thenReturn(List.of(d));
        when(bindingMapper.selectCount(any())).thenReturn(0L); // 无匹配绑定

        List<Map<String, Object>> devices = service.listDevices(null, UUID.randomUUID());

        assertThat(devices).isEmpty();
    }

    @Test
    @DisplayName("设备详情：含档案 + 绑定历史")
    void getDeviceDetailOk() {
        Device d = sampleDevice();
        when(deviceMapper.selectById(deviceId)).thenReturn(d);
        DeviceBinding b = new DeviceBinding();
        b.setBindType(DeviceBinding.BIND_TYPE_CLASS);
        b.setBindTargetId(UUID.randomUUID());
        b.setStatus(DeviceBinding.STATUS_ACTIVE);
        b.setBoundAt(Instant.now());
        when(bindingMapper.selectList(any())).thenReturn(List.of(b));

        Map<String, Object> detail = service.getDeviceDetail(deviceId);

        assertThat(detail.get("deviceCode")).isEqualTo(deviceCode);
        assertThat(detail.get("sn")).isEqualTo("BB-2026-000123");
        assertThat((List<?>) detail.get("bindings")).hasSize(1);
    }

    @Test
    @DisplayName("设备详情：不存在返回 null")
    void getDeviceDetailNotFound() {
        when(deviceMapper.selectById(deviceId)).thenReturn(null);
        assertThat(service.getDeviceDetail(deviceId)).isNull();
    }

    @Test
    @DisplayName("二维码批量签发：合法设备落库 + 返回印刷包，未找到设备单独列出")
    void exportQrOk() {
        Device d = sampleDevice();
        // 连续 stub：合法码第一次查询返回设备，未知码第二次查询返回 null
        when(deviceMapper.selectOne(any())).thenReturn(d, null);

        Map<String, Object> result = service.exportQr(List.of(deviceCode, "NOT_EXIST"), "admin-1");

        assertThat(result.get("issuedCount")).isEqualTo(1);
        assertThat((List<String>) result.get("notFound")).contains("NOT_EXIST");
        verify(qrIssuanceMapper).insert(any(DeviceQrIssuance.class));
    }

    @Test
    @DisplayName("批量操作：合法 action 受理，非法 action 拒绝")
    void batchOperationValidatesAction() {
        Device d = sampleDevice();
        when(deviceMapper.selectOne(any())).thenReturn(d);

        Map<String, Object> ok = service.batchOperation(List.of(deviceCode), "ota", "admin-1");
        assertThat(ok.get("acceptedCount")).isEqualTo(1);
        assertThat(ok.get("action")).isEqualTo("ota");

        assertThatThrownBy(() -> service.batchOperation(List.of(deviceCode), "hack", "admin-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法操作类型");
    }

    @Test
    @DisplayName("批量操作：不存在的设备列入 notFound")
    void batchOperationNotFound() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> result = service.batchOperation(List.of("GHOST"), "reboot", "admin-1");
        assertThat(result.get("acceptedCount")).isEqualTo(0);
        assertThat((List<String>) result.get("notFound")).contains("GHOST");
    }
}
