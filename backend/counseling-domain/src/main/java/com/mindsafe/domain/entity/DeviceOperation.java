package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备操作审计（对应 tenant_template.device_operations，V45）
 * <p>
 * P1：batchOperation/ota/reboot/factory-reset 受理留痕（审计发现无落库，管理员误以为已执行）。
 */
@TableName(value = "device_operations", schema = "tenant_template")
public class DeviceOperation {

    @TableId(type = IdType.INPUT)
    private UUID operationId;

    private String deviceCode;

    /** ota/reboot/factory-reset/batch-ota/... */
    private String action;

    /** 操作人（平台 admin 用户名） */
    private String operator;

    private Instant acceptedAt;

    private String note;

    public UUID getOperationId() {
        return operationId;
    }

    public void setOperationId(UUID operationId) {
        this.operationId = operationId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}