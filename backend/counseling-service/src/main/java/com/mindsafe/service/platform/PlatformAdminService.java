package com.mindsafe.service.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.PlatformAdmin;
import com.mindsafe.domain.mapper.PlatformAdminMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 平台管理员服务（ADMIN-P0-02，M6 平台基础）
 * <p>
 * 独立 platform_admin 账号体系（DEC-007）：独立登录端点 + PLATFORM_ token 前缀，
 * 与租户 users 表解耦。登录校验 BCrypt + 状态检查 + last_login_at 留痕。
 * 设计见 doing/83 后台管理端 §5.6/§7.6。
 */
@Service
public class PlatformAdminService {

    private final PlatformAdminMapper platformAdminMapper;
    private final PasswordEncoder passwordEncoder;

    public PlatformAdminService(PlatformAdminMapper platformAdminMapper,
                                PasswordEncoder passwordEncoder) {
        this.platformAdminMapper = platformAdminMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 平台管理员登录。用户名不存在/密码错误统一返回 UNAUTHORIZED（不泄露账号存在性）；
     * 禁用账号拒绝登录。
     */
    public PlatformAdmin login(String username, String rawPassword) {
        PlatformAdmin admin = platformAdminMapper.selectOne(
                new LambdaQueryWrapper<PlatformAdmin>()
                        .eq(PlatformAdmin::getUsername, username));
        if (admin == null || !passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!PlatformAdmin.STATUS_ACTIVE.equals(admin.getStatus())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已禁用");
        }
        admin.setLastLoginAt(Instant.now());
        platformAdminMapper.updateById(admin);
        return admin;
    }
}
