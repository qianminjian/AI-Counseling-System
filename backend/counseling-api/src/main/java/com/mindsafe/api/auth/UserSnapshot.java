package com.mindsafe.api.auth;

import com.mindsafe.domain.entity.User;

import java.util.UUID;

/**
 * 用户展示快照（F9：替代 User 实体在 api 层流转）。
 * <p>
 * 仅含展示/会话所需字段（familyCode 等），不含 passwordHash/pinHash 敏感字段，
 * 用于 /me、试用注册响应等仅需字段读取的场景。
 */
public record UserSnapshot(
        UUID userId,
        UUID tenantId,
        UUID schoolId,
        String userType,
        String pseudonym,
        String gradeCode,
        String classCode,
        String status,
        String familyCode,
        Boolean mustChangePassword
) {
    /** 从 service 层返回的 User 实体瞬时转换 */
    public static UserSnapshot from(User u) {
        if (u == null) return null;
        return new UserSnapshot(u.getUserId(), u.getTenantId(), u.getSchoolId(), u.getUserType(),
                u.getPseudonym(), u.getGradeCode(), u.getClassCode(), u.getStatus(), u.getFamilyCode(),
                u.getMustChangePassword());
    }
}
