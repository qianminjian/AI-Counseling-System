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

    /** M1 简化：明文密码哈希（后续迁移到加密字段） */
    @TableField(exist = false)
    private String passwordHash;

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
}
