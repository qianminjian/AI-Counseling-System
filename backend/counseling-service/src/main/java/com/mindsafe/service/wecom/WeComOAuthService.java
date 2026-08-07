package com.mindsafe.service.wecom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 企微 OAuth 用户服务（T4 批次B：教师匹配 + 最后登录时间更新下沉，Controller 不再直查 Mapper）。
 * <p>
 * 前置认证链路（无 JWT）显式声明系统作用域（M1-003 fail-fast 配套）在 Service 内完成。
 */
@Service
public class WeComOAuthService {

    private final UserMapper userMapper;

    public WeComOAuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 按企微 userId（pseudonym 字段暂存）匹配教师（跨租户，系统作用域查询） */
    public User findTeacherByWeComId(String wecomUserId) {
        return TenantContextHolder.callAsSystem(() -> userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPseudonym, wecomUserId)
                        .eq(User::getUserType, User.USER_TYPE_TEACHER)
                        .last("LIMIT 1")));
    }

    /** 更新最后登录时间（已识别出租户，绑定真实租户上下文执行） */
    public void touchLastLogin(UUID tenantId, UUID userId) {
        TenantContextHolder.set(tenantId);
        try {
            User loginUpdate = new User();
            loginUpdate.setUserId(userId);
            loginUpdate.setLastLoginAt(Instant.now());
            userMapper.updateById(loginUpdate);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
