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

    /** C2（2026-08-05）：状态魔法值收敛——用户启用 */
    public static final String STATUS_ACTIVE = "active";

    /** C2（2026-08-05）：状态魔法值收敛——用户停用（租户暂停时批量下架） */
    public static final String STATUS_SUSPENDED = "suspended";

    /** C2（2026-08-05）：状态魔法值收敛——用户待启用（老师后台审批/初始化） */
    public static final String STATUS_PENDING = "pending";

    /** F-1（2026-08-09）：状态魔法值收敛——撤回同意冻结（PIPL §47 家长可撤回，需重新授权恢复） */
    public static final String STATUS_WITHDRAWN = "withdrawn";

    /** C2（2026-08-05）：用户类型魔法值收敛——学生 */
    public static final String USER_TYPE_STUDENT = "student";

    /** C2（2026-08-05）：用户类型魔法值收敛——老师 */
    public static final String USER_TYPE_TEACHER = "teacher";

    /** C2（2026-08-05）：用户类型魔法值收敛——心理老师 */
    public static final String USER_TYPE_PSYCH_TEACHER = "psych_teacher";

    /** C2（2026-08-05）：用户类型魔法值收敛——班主任 */
    public static final String USER_TYPE_CLASS_TEACHER = "class_teacher";

    /** C2（2026-08-05）：用户类型魔法值收敛——管理员 */
    public static final String USER_TYPE_ADMIN = "admin";

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

    /** 家庭码（6位字母数字，学生注册时生成，家长绑定凭证） */
    private String familyCode;

    /** 方言偏好（管理端配置，可为空，design/56 §三：cantonese/northeastern/sichuan/henan/shandong/hunan/shaanxi/anhui） */
    private String dialect;

    public User() {
    }

    public static User createStudent(UUID tenantId, UUID schoolId, String pseudonym,
                                     String gradeCode, String classCode) {
        User u = new User();
        u.tenantId = tenantId;
        u.schoolId = schoolId;
        u.userType = USER_TYPE_STUDENT;
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

    public String getFamilyCode() { return familyCode; }
    public void setFamilyCode(String familyCode) { this.familyCode = familyCode; }

    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
}
