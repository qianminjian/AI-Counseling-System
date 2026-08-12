package com.mindsafe.api.auth;

import com.mindsafe.domain.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * 登录候选最小身份快照（F9：替代 User 实体在认证链下传）。
 * <p>
 * 仅含密码登录链路所需的字段（含 passwordHash 用于凭据匹配），
 * 不再携带 pinHash/familyCode/gender/dialect 等无关字段；
 * 该快照只在 api 模块内部流转，永不进入任何响应体。
 */
public record LoginCandidate(
        UUID userId,
        UUID tenantId,
        String userType,
        String pseudonym,
        String gradeCode,
        String classCode,
        String status,
        String passwordHash,
        Boolean mustChangePassword,
        Instant passwordChangedAt
) {
    /** 从 service 层返回的 User 实体瞬时转换（仅此处接触实体，之后全程快照；null → null 供调用方判不可用） */
    public static LoginCandidate from(User u) {
        if (u == null) return null;
        return new LoginCandidate(u.getUserId(), u.getTenantId(), u.getUserType(), u.getPseudonym(),
                u.getGradeCode(), u.getClassCode(), u.getStatus(), u.getPasswordHash(),
                u.getMustChangePassword(), u.getPasswordChangedAt());
    }
}
