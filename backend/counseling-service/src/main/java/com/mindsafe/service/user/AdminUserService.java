package com.mindsafe.service.user;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理端用户管理服务（T4 批次B：密码重置下沉，含同租户归属校验 + 审计，Controller 不再直查 Mapper）。
 */
@Service
public class AdminUserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public AdminUserService(UserMapper userMapper,
                            PasswordEncoder passwordEncoder,
                            AuditLogService auditLogService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * 管理员重置用户密码（教师/学生忘记密码时由管理员操作）。
     * 重置后 must_change_password = true，用户下次登录必须改密。
     * 返回被重置用户（用于响应展示），同租户校验失败抛 FORBIDDEN。
     */
    @Transactional
    public User resetPassword(UUID tenantId, UUID operatorUserId, UUID userId, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        // 只能重置同租户用户
        if (!user.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作其他租户用户");
        }

        // 更新密码 + 强制改密标记
        User update = new User();
        update.setUserId(userId);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        update.setMustChangePassword(true);
        update.setPasswordChangedAt(Instant.now());
        userMapper.updateById(update);

        // 审计
        auditLogService.log(tenantId, operatorUserId, "RESET_PASSWORD", "user", userId, null);
        return user;
    }
}
