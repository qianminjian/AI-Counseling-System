package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备二维码签发记录（对应 tenant_template.device_qr_issuance，V39）
 * <p>
 * CFG-005：机身/包装二维码批量印制留痕（URL 原文 + 签发人），供回溯与
 * 换码审计。设计见 doing/84 §六.1。
 */
@TableName(value = "device_qr_issuance", schema = "tenant_template")
public class DeviceQrIssuance {

    @TableId(value = "issuance_id", type = IdType.INPUT)
    private UUID issuanceId;

    private UUID deviceId;

    /** 管理员（批量印制签发人） */
    private String issuedBy;

    /** 印刷的 URL 原文（版本留痕） */
    private String qrPayload;

    private Instant issuedAt;

    public UUID getIssuanceId() {
        return issuanceId;
    }

    public void setIssuanceId(UUID issuanceId) {
        this.issuanceId = issuanceId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(String issuedBy) {
        this.issuedBy = issuedBy;
    }

    public String getQrPayload() {
        return qrPayload;
    }

    public void setQrPayload(String qrPayload) {
        this.qrPayload = qrPayload;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }
}
