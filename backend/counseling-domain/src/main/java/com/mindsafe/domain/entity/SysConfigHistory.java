package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 配置变更历史实体（对应 tenant_template.sys_config_history，V36）
 * <p>
 * M1 配置变更留痕（SECRET 存掩码标记）。设计见 doing/83 后台管理端 §6.2。
 */
@TableName(value = "sys_config_history", schema = "tenant_template")
public class SysConfigHistory {

    @TableId(value = "history_id", type = IdType.INPUT)
    private UUID historyId;

    private String configKey;

    /** 变更前值快照 */
    private String oldValue;

    /** 变更后值快照（SECRET 存掩码标记） */
    private String newValue;

    /** 操作人（platform_admin 账号） */
    private String changedBy;

    /** 变更原因（必填） */
    private String reason;

    private Instant changedAt;

    public UUID getHistoryId() {
        return historyId;
    }

    public void setHistoryId(UUID historyId) {
        this.historyId = historyId;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }
}
