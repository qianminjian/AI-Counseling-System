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

    /**
     * 查询设备偏好（家庭管理通道授权：调用方须已校验家庭-设备绑定）。
     * AD-005（2026-08-11）授权语义统一：偏好数据按 deviceCode 全局唯一（平台级表），
     * "谁的偏好"不变量 = 设备归属——两条读取路径的授权由调用通道保证：
     * 本方法=家庭通道（绑定校验），preferencesForPull=设备通道（DVC_ token，见 pullConfig）。
     */
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

    /**
     * 设备配置拉取下发用（DeviceService.pullConfig 调用）。
     * AD-005（2026-08-11）授权语义统一：本方法仅经设备通道消费——已绑定设备由
     * pullConfig 强制 DVC_ token 校验（AUDIT-DEEP-002）；未绑定设备无偏好（绑定后
     * 才可设置）返回 null，匿名拉取无泄漏面。与 getPreferences（家庭通道）共享
     * "设备归属"不变量，两种授权语义已收口到调用通道。
     */
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
