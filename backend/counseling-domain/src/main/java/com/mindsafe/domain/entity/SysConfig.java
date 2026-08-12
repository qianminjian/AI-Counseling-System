package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 配置注册表实体（对应 tenant_template.sys_config，V36）
 * <p>
 * M1 系统配置管理：配置面板（SECRET 掩码 + HOT/RESTART 两级，仅标记 HOT 开放修改）。
 * 设计见 doing/83 后台管理端 §6.1。
 */
@TableName(value = "sys_config", schema = TenantSchema.TENANT_TEMPLATE)
public class SysConfig {

    /** 敏感度：值可读 */
    public static final String SENSITIVE_NORMAL = "NORMAL";

    /** 敏感度：仅显示已配置/未配置，值永不出 API */
    public static final String SENSITIVE_SECRET = "SECRET";

    /** 生效方式：修改即时生效 */
    public static final String EFFECT_HOT = "HOT";

    /** 生效方式：需重启生效 */
    public static final String EFFECT_RESTART = "RESTART";

    /** SECRET 值掩码标记（存储与回显均用） */
    public static final String SECRET_MASK = "***已配置***";

    @TableId(value = "config_id", type = IdType.INPUT)
    private UUID configId;

    /** 配置键（唯一，如 mindsafe.safety.voiceprint-threshold） */
    private String configKey;

    /** 配置域：system/security/voice/chat/alert/commercial */
    private String domain;

    /** 配置值（SECRET 类存掩码标记，值不回读） */
    private String value;

    /** string/number/bool/json */
    private String valueType;

    /** NORMAL/SECRET */
    private String sensitive;

    /** HOT/RESTART */
    private String effectMode;

    /** application.yml/env/python-config/db */
    private String source;

    /** 说明 */
    private String description;

    /** 最近变更信息 */
    private Instant updatedAt;

    /** 最近变更人 */
    private String updatedBy;

    public UUID getConfigId() {
        return configId;
    }

    public void setConfigId(UUID configId) {
        this.configId = configId;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public String getSensitive() {
        return sensitive;
    }

    public void setSensitive(String sensitive) {
        this.sensitive = sensitive;
    }

    public String getEffectMode() {
        return effectMode;
    }

    public void setEffectMode(String effectMode) {
        this.effectMode = effectMode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
