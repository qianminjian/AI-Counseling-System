package com.mindsafe.service.toc;

import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.mapper.DeviceBindingMapper;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.service.device.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocDeviceService 测试（doing/85 TOC-003）
 * 覆盖：绑定（FAMILY 类型 + 家庭账号归属 + 可选孩子档案）、解绑、家庭设备列表（FAMILY 过滤）。
 */
class TocDeviceServiceTest {

    private DeviceService deviceService;
    private com.mindsafe.domain.mapper.DeviceMapper deviceMapper;
    private com.mindsafe.domain.mapper.DeviceBindingMapper bindingMapper;
    private TocDeviceService service;

    private final UUID familyAccountId = UUID.randomUUID();
    private final String deviceCode = "K7M2P9XW4AQ";

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        deviceMapper = mock(com.mindsafe.domain.mapper.DeviceMapper.class);
        bindingMapper = mock(com.mindsafe.domain.mapper.DeviceBindingMapper.class);
        service = new TocDeviceService(deviceService, bindingMapper, deviceMapper);
    }

    @Test
    @DisplayName("bind：委托 DeviceService 以 FAMILY 类型 + 家庭账号绑定")
    void bindDelegatesWithFamilyType() {
        UUID profileId = UUID.randomUUID();
        when(deviceService.bind(deviceCode, DeviceBinding.BIND_TYPE_FAMILY, familyAccountId,
                profileId, "123456", "acc-1"))
                .thenReturn(Map.of("status", "ONLINE_BOUND"));

        var result = service.bind(familyAccountId, deviceCode, profileId, "123456", "acc-1");

        assertThat(result.get("status")).isEqualTo("ONLINE_BOUND");
        verify(deviceService).bind(deviceCode, DeviceBinding.BIND_TYPE_FAMILY,
                familyAccountId, profileId, "123456", "acc-1");
    }

    @Test
    @DisplayName("bind：无孩子档案（null profileId）也可绑定（家庭级）")
    void bindWithoutProfile() {
        when(deviceService.bind(deviceCode, DeviceBinding.BIND_TYPE_FAMILY, familyAccountId,
                null, "123456", "acc-1"))
                .thenReturn(Map.of("status", "ONLINE_BOUND"));
        service.bind(familyAccountId, deviceCode, null, "123456", "acc-1");
        verify(deviceService).bind(deviceCode, DeviceBinding.BIND_TYPE_FAMILY,
                familyAccountId, null, "123456", "acc-1");
    }

    @Test
    @DisplayName("unbind：委托 DeviceService（AD-004 归属校验通过后）")
    void unbindDelegates() {
        // AD-004：unbind 前置校验设备存在且属于该家庭——mock 设备与绑定计数
        Device device = new Device();
        device.setDeviceId(java.util.UUID.randomUUID());
        device.setDeviceCode(deviceCode);
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(bindingMapper.selectCount(any())).thenReturn(1L);

        service.unbind(familyAccountId, deviceCode, "acc-1");
        verify(deviceService).unbind(deviceCode, "acc-1");
    }

    @Test
    @DisplayName("listDevices：按 FAMILY + 家庭账号过滤")
    void listDevicesFilteredByFamily() {
        when(deviceService.listDevices(DeviceBinding.BIND_TYPE_FAMILY, familyAccountId))
                .thenReturn(List.of());
        assertThat(service.listDevices(familyAccountId)).isEmpty();
        verify(deviceService).listDevices(DeviceBinding.BIND_TYPE_FAMILY, familyAccountId);
    }
}
