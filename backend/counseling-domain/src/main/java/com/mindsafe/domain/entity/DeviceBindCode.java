package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备绑定验证码会话（对应 tenant_template.device_bind_codes，V39）
 * <p>
 * CFG-004：验证码 SHA-256 哈希存储（不存明文、不落日志）、5 分钟有效、
 * 3 次失败锁定 5 分钟、绑定成功即作废（一次性）。
 * 设计见 doing/84 §五.3/§六.1。
 */
@TableName(value = "device_bind_codes", schema = TenantSchema.TENANT_TEMPLATE)
public class DeviceBindCode {

    /** 验证码有效期（分钟） */
    public static final long CODE_TTL_MINUTES = 5L;

    /** 最大失败次数（达此值锁定） */
    public static final int MAX_FAIL_COUNT = 3;

    /** 锁定时长（分钟） */
    public static final long LOCK_MINUTES = 5L;

    /** 验证码位数 */
    public static final int CODE_LENGTH = 6;

    @TableId(value = "code_id", type = IdType.INPUT)
    private UUID codeId;

    private UUID deviceId;

    /** 验证码 SHA-256 哈希 */
    private String codeHash;

    private Instant expiresAt;

    /** 连续失败次数 */
    private Integer failCount;

    /** 锁定截止时间 */
    private Instant lockedUntil;

    /** 一次性：绑定成功即作废 */
    private Instant usedAt;

    private Instant createdAt;

    public UUID getCodeId() {
        return codeId;
    }

    public void setCodeId(UUID codeId) {
        this.codeId = codeId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getFailCount() {
        return failCount;
    }

    public void setFailCount(Integer failCount) {
        this.failCount = failCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
