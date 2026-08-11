package com.mindsafe.service.toc;

import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.service.device.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocDeviceService 测试（doing/85 TOC-003）
 * 覆盖：绑定（FAMILY 类型 + 家庭账号归属 + 可选孩子档案）、解绑、家庭设备列表（FAMILY 过滤）。
 */
class TocDeviceServiceTest {

    private DeviceService deviceService;
    private TocDeviceService service;

    private final UUID familyAccountId = UUID.randomUUID();
    private final String deviceCode = "K7M2P9XW4AQ";

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        service = new TocDeviceService(deviceService, mock(com.mindsafe.domain.mapper.DeviceBindingMapper.class), mock(DeviceMapper.class));
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
    @DisplayName("unbind：委托 DeviceService")
    void unbindDelegates() {
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
