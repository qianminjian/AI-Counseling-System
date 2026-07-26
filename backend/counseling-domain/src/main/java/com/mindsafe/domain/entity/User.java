package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户实体（对应 tenant_template.users）
 * M1 简化：不含加密字段，使用明文 pseudonym 作为显示名
 */
@TableName(value = "users", schema = "tenant_template")
public class User {

    @TableId(value = "user_id", type = IdType.INPUT)
    private UUID userId;

    private UUID tenantId;
    private UUID schoolId;
    private String userType;
    private String pseudonym;
    private String gradeCode;
    private String classCode;
    private String status;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;

    /** 密码哈希（BCrypt） */
    private String passwordHash;

    /** 首次登录强制改密标记（方案 B：临时密码 + 首次改密） */
    private Boolean mustChangePassword;

    /** 性别（male/female），用于对话风格、TTS 音色、界面主题个性化 */
    private String gender;

    /** PIN码 BCrypt 哈希（4-6位数字，学生快捷登录用） */
    private String pinHash;
    /** PIN码设置时间 */
    private Instant pinSetAt;

    /** 最近一次密码修改时间（AUTH-014：用于 90 天过期判断） */
    private Instant passwordChangedAt;

    public User() {
    }

    public static User createStudent(UUID tenantId, UUID schoolId, String pseudonym,
                                     String gradeCode, String classCode) {
        User u = new User();
        u.tenantId = tenantId;
        u.schoolId = schoolId;
        u.userType = "student";
        u.pseudonym = pseudonym;
        u.gradeCode = gradeCode;
        u.classCode = classCode;
        u.status = "active";
        u.createdAt = Instant.now();
        u.updatedAt = Instant.now();
        return u;
    }

    public static User createTeacher(UUID tenantId, UUID schoolId, String pseudonym, String userType) {
        User u = new User();
        u.tenantId = tenantId;
        u.schoolId = schoolId;
        u.userType = userType;
        u.pseudonym = pseudonym;
        u.status = "active";
        u.createdAt = Instant.now();
        u.updatedAt = Instant.now();
        return u;
    }

    // ===== Getters & Setters =====

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getSchoolId() { return schoolId; }
    public void setSchoolId(UUID schoolId) { this.schoolId = schoolId; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public String getPseudonym() { return pseudonym; }
    public void setPseudonym(String pseudonym) { this.pseudonym = pseudonym; }

    public String getGradeCode() { return gradeCode; }
    public void setGradeCode(String gradeCode) { this.gradeCode = gradeCode; }

    public String getClassCode() { return classCode; }
    public void setClassCode(String classCode) { this.classCode = classCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Boolean getMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(Boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    public Instant getPinSetAt() { return pinSetAt; }
    public void setPinSetAt(Instant pinSetAt) { this.pinSetAt = pinSetAt; }

    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(Instant passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
}
