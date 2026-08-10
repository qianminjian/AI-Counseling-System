package com.mindsafe.service.device;

import com.mindsafe.domain.entity.DevicePreference;
import com.mindsafe.domain.mapper.DevicePreferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DevicePreferenceService 测试（doing/85 TOC-006）
 * 覆盖：查询（家庭隔离）、设置（upsert + 音量范围校验）、设备配置下发（preferencesForPull）。
 */
class DevicePreferenceServiceTest {

    private DevicePreferenceMapper preferenceMapper;
    private DevicePreferenceService service;

    private final UUID familyAccountId = UUID.randomUUID();
    private final String deviceCode = "K7M2P9XW4AQ";

    @BeforeEach
    void setUp() {
        preferenceMapper = mock(DevicePreferenceMapper.class);
        service = new DevicePreferenceService(preferenceMapper);
    }

    @Test
    @DisplayName("get：无偏好返回 null（家庭隔离查询）")
    void getNone() {
        when(preferenceMapper.selectOne(any())).thenReturn(null);
        assertThat(service.getPreferences(familyAccountId, deviceCode)).isNull();
    }

    @Test
    @DisplayName("set：新建设备偏好（upsert insert）")
    void setCreates() {
        // 连续 stub：第一次 find=null（判断新建）→ insert 后回查返回已建记录
        DevicePreference inserted = new DevicePreference();
        inserted.setPrefId(UUID.randomUUID());
        inserted.setDeviceCode(deviceCode);
        inserted.setFamilyAccountId(familyAccountId);
        inserted.setVolume(60);
        inserted.setVoicePersona("qingyu");
        when(preferenceMapper.selectOne(any())).thenReturn(null, inserted);
        var result = service.setPreferences(familyAccountId, deviceCode, 60, "qingyu", "gentle");
        assertThat(result.get("volume")).isEqualTo(60);
        assertThat(result.get("voicePersona")).isEqualTo("qingyu");
        verify(preferenceMapper).insert(any(DevicePreference.class));
    }

    @Test
    @DisplayName("set：音量越界拒绝")
    void setInvalidVolume() {
        assertThatThrownBy(() -> service.setPreferences(familyAccountId, deviceCode, 150, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0-100");
    }

    @Test
    @DisplayName("set：已存在则更新（upsert update）")
    void setUpdates() {
        DevicePreference existing = new DevicePreference();
        existing.setPrefId(UUID.randomUUID());
        existing.setDeviceCode(deviceCode);
        existing.setFamilyAccountId(familyAccountId);
        when(preferenceMapper.selectOne(any())).thenReturn(existing);

        service.setPreferences(familyAccountId, deviceCode, 30, "xiaobobo", null);

        assertThat(existing.getVolume()).isEqualTo(30);
        assertThat(existing.getVoicePersona()).isEqualTo("xiaobobo");
        verify(preferenceMapper).updateById(existing);
    }

    @Test
    @DisplayName("preferencesForPull：设备配置下发（匿名通道按 deviceCode 查）")
    void preferencesForPullOk() {
        DevicePreference existing = new DevicePreference();
        existing.setVolume(50);
        existing.setVoicePersona("qingyu");
        when(preferenceMapper.selectOne(any())).thenReturn(existing);

        Map<String, Object> prefs = service.preferencesForPull(deviceCode);

        assertThat(prefs.get("volume")).isEqualTo(50);
        assertThat(prefs.get("voicePersona")).isEqualTo("qingyu");
    }
}
