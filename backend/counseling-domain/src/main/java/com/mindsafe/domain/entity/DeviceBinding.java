package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备绑定关系（对应 tenant_template.device_bindings，V39）
 * <p>
 * CFG-004：toB 学校/班级/咨询室三层归属绑定；toB 多人共用 student_id 为 NULL。
 * 设计见 doing/84 §六.1。
 */
@TableName(value = "device_bindings", schema = "tenant_template")
public class DeviceBinding {

    /** 绑定类型：学校 */
    public static final String BIND_TYPE_SCHOOL = "SCHOOL";

    /** 绑定类型：班级 */
    public static final String BIND_TYPE_CLASS = "CLASS";

    /** 绑定类型：咨询室 */
    public static final String BIND_TYPE_ROOM = "ROOM";

    /** 绑定类型：家庭（toC 预留） */
    public static final String BIND_TYPE_FAMILY = "FAMILY";

    /** 状态：生效 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已解绑 */
    public static final String STATUS_UNBOUND = "UNBOUND";

    @TableId(value = "binding_id", type = IdType.INPUT)
    private UUID bindingId;

    private UUID deviceId;

    /** SCHOOL/CLASS/ROOM/FAMILY */
    private String bindType;

    /** 学校/班级/咨询室/家庭 ID */
    private UUID bindTargetId;

    /** toC 单孩绑定；toB 多人共用为 NULL */
    private UUID studentId;

    /** 操作人（老师/家长用户 ID） */
    private String boundBy;

    /** ACTIVE/UNBOUND */
    private String status;

    private Instant boundAt;

    private Instant unboundAt;

    public UUID getBindingId() {
        return bindingId;
    }

    public void setBindingId(UUID bindingId) {
        this.bindingId = bindingId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getBindType() {
        return bindType;
    }

    public void setBindType(String bindType) {
        this.bindType = bindType;
    }

    public UUID getBindTargetId() {
        return bindTargetId;
    }

    public void setBindTargetId(UUID bindTargetId) {
        this.bindTargetId = bindTargetId;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public void setStudentId(UUID studentId) {
        this.studentId = studentId;
    }

    public String getBoundBy() {
        return boundBy;
    }

    public void setBoundBy(String boundBy) {
        this.boundBy = boundBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Instant boundAt) {
        this.boundAt = boundAt;
    }

    public Instant getUnboundAt() {
        return unboundAt;
    }

    public void setUnboundAt(Instant unboundAt) {
        this.unboundAt = unboundAt;
    }
}
