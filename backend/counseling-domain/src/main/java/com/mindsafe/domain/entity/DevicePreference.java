package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备偏好（对应 tenant_template.device_preferences，V43）
 * <p>
 * TOC-006 远程管理软件侧：家长设置设备偏好（音量/音色/对话偏好），
 * 设备端拉取配置时下发；按 family_account_id 隔离（doing/85 §四）。
 */
@TableName(value = "device_preferences", schema = "tenant_template")
public class DevicePreference {

    @TableId(type = IdType.INPUT)
    private UUID prefId;

    /** 设备短码 */
    private String deviceCode;

    /** 归属家庭账号（toC 数据隔离键） */
    private UUID familyAccountId;

    /** 音量 0-100（NULL=未设置） */
    private Integer volume;

    /** 音色（VoicePersonaResolver 对齐） */
    private String voicePersona;

    /** 对话偏好 */
    private String dialoguePref;

    private Instant updatedAt;

    public UUID getPrefId() {
        return prefId;
    }

    public void setPrefId(UUID prefId) {
        this.prefId = prefId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public UUID getFamilyAccountId() {
        return familyAccountId;
    }

    public void setFamilyAccountId(UUID familyAccountId) {
        this.familyAccountId = familyAccountId;
    }

    public Integer getVolume() {
        return volume;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }

    public String getVoicePersona() {
        return voicePersona;
    }

    public void setVoicePersona(String voicePersona) {
        this.voicePersona = voicePersona;
    }

    public String getDialoguePref() {
        return dialoguePref;
    }

    public void setDialoguePref(String dialoguePref) {
        this.dialoguePref = dialoguePref;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
