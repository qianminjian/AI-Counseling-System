package com.mindsafe.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.DevicePreference;
import com.mindsafe.domain.mapper.DevicePreferenceMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 设备偏好服务（TOC-006 远程管理软件侧，doing/85 §四）
 * <p>
 * 家长设置设备偏好（音量/音色/对话偏好），按 family_account_id 隔离；
 * 设备端拉取配置（pullConfig）时下发。真实固件执行属 NST-HW-02 二期。
 */
@Service
public class DevicePreferenceService {

    private final DevicePreferenceMapper preferenceMapper;

    public DevicePreferenceService(DevicePreferenceMapper preferenceMapper) {
        this.preferenceMapper = preferenceMapper;
    }

    /** 查询设备偏好（按家庭隔离，非本人返回 null）。 */
    public Map<String, Object> getPreferences(UUID familyAccountId, String deviceCode) {
        DevicePreference pref = find(familyAccountId, deviceCode);
        if (pref == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("volume", pref.getVolume());
        result.put("voicePersona", pref.getVoicePersona());
        result.put("dialoguePref", pref.getDialoguePref());
        return result;
    }

    /**
     * 设置偏好（家庭归属 upsert）：音量 0-100 校验。
     */
    public Map<String, Object> setPreferences(UUID familyAccountId, String deviceCode,
                                              Integer volume, String voicePersona, String dialoguePref) {
        if (volume != null && (volume < 0 || volume > 100)) {
            throw new IllegalArgumentException("音量范围为 0-100");
        }
        DevicePreference pref = find(familyAccountId, deviceCode);
        Instant now = Instant.now();
        if (pref == null) {
            pref = new DevicePreference();
            pref.setPrefId(UUID.randomUUID());
            pref.setDeviceCode(deviceCode);
            pref.setFamilyAccountId(familyAccountId);
            pref.setVolume(volume);
            pref.setVoicePersona(voicePersona);
            pref.setDialoguePref(dialoguePref);
            pref.setUpdatedAt(now);
            preferenceMapper.insert(pref);
        } else {
            pref.setVolume(volume);
            pref.setVoicePersona(voicePersona);
            pref.setDialoguePref(dialoguePref);
            pref.setUpdatedAt(now);
            preferenceMapper.updateById(pref);
        }
        return getPreferences(familyAccountId, deviceCode);
    }

    /** 设备配置拉取下发用（DeviceService.pullConfig 调用，匿名设备通道按 deviceCode 查）。 */
    public Map<String, Object> preferencesForPull(String deviceCode) {
        DevicePreference pref = preferenceMapper.selectOne(
                new LambdaQueryWrapper<DevicePreference>()
                        .eq(DevicePreference::getDeviceCode, deviceCode)
                        .last("LIMIT 1"));
        if (pref == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("volume", pref.getVolume());
        result.put("voicePersona", pref.getVoicePersona());
        result.put("dialoguePref", pref.getDialoguePref());
        return result;
    }

    private DevicePreference find(UUID familyAccountId, String deviceCode) {
        return preferenceMapper.selectOne(
                new LambdaQueryWrapper<DevicePreference>()
                        .eq(DevicePreference::getFamilyAccountId, familyAccountId)
                        .eq(DevicePreference::getDeviceCode, deviceCode)
                        .last("LIMIT 1"));
    }
}
