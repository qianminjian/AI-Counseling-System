package com.mindsafe.service.voiceprint;

import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.auth.TenantAccessGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 声纹登录用户服务（T4 批次B：声纹匹配后的用户查询 / 最后登录时间更新下沉，Controller 不再直查 Mapper）。
 * <p>
 * 登录门禁（账号状态 + 租户可用性）随查询下沉，Controller 仅组装响应。
 */
@Service
public class VoiceprintLoginService {

    private final UserMapper userMapper;
    private final TenantAccessGuard tenantAccessGuard;

    public VoiceprintLoginService(UserMapper userMapper, TenantAccessGuard tenantAccessGuard) {
        this.userMapper = userMapper;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * 查询可登录用户（null 表示账号不存在/已停用/租户不可登录）。
     * 门禁判定（SEC-004 租户状态 + 账号状态）随查询下沉。
     */
    public User findLoginAllowedUser(UUID userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !User.STATUS_ACTIVE.equals(user.getStatus())
                || !tenantAccessGuard.isLoginAllowed(user.getTenantId())) {
            return null;
        }
        return user;
    }

    /** 更新最后登录时间 */
    public void touchLastLogin(UUID userId) {
        User update = new User();
        update.setUserId(userId);
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);
    }
}
