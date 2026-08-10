package com.mindsafe.api.dto.device;

import java.util.UUID;

/**
 * 设备绑定请求（CFG-004，doing/84 §四.3）
 */
public class BindDeviceRequest {

    /** SCHOOL/CLASS/ROOM（toB）/ FAMILY（toC 预留） */
    private String bindType;

    /** 学校/班级/咨询室/家庭 ID */
    private UUID bindTargetId;

    /** toC 单孩绑定；toB 多人共用可空 */
    private UUID studentId;

    /** 设备语音播报的 6 位绑定验证码 */
    private String code;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
