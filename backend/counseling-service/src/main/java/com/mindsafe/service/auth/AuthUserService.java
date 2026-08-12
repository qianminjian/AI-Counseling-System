package com.mindsafe.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 认证用户服务（T4 批次B/C：登录候选查询 / 登录成功留痕 / 用户查询下沉，Controller 不再直查 Mapper）。
 * <p>
 * 前置认证链路（无 JWT，跨租户按昵称查用户）显式声明系统作用域（M1-003 fail-fast 配套）。
 */
@Service
public class AuthUserService {

    private final UserMapper userMapper;
    private final AuditLogService auditLogService;

    public AuthUserService(UserMapper userMapper, AuditLogService auditLogService) {
        this.userMapper = userMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 按昵称查登录候选（SEC-003：昵称无全局唯一约束，重名拒绝逻辑在调用方判定）。
     * 系统作用域查询，不受租户行隔离影响。
     * F-1：查询放宽至含 withdrawn（撤回同意冻结）——冻结账号须返回专属提示而非笼统的"用户名或密码错误"。
     */
    public List<User> findLoginCandidates(String username) {
        return TenantContextHolder.callAsSystem(() -> userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPseudonym, username)
        ));
    }

    /** 登录成功：更新最后登录时间 + 审计（P2-4，doing/97：收敛至 callAsSystem，异常路径自动 clear） */
    public void recordLoginSuccess(UUID tenantId, UUID userId) {
        TenantContextHolder.callAsSystem(() -> {
            User update = new User();
            update.setUserId(userId);
            update.setLastLoginAt(Instant.now());
            userMapper.updateById(update);

            // 审计：登录成功（@Async 经 TaskDecorator 继承本线程租户上下文）
            auditLogService.log(tenantId, userId, "LOGIN", "user", userId, null);
            return null;
        });
    }

    /** 按 ID 查询用户（受租户行隔离，供已认证链路使用） */
    public User findById(UUID userId) {
        return userMapper.selectById(userId);
    }

    /**
     * 按 ID 查询用户（系统作用域，供前置认证链路使用——调用方须自行校验租户归属）。
     * 示例：声纹登录 / 试用注册响应期（无 JWT，M1-003 fail-fast 配套）。
     */
    public User findByIdAsSystem(UUID userId) {
        return TenantContextHolder.callAsSystem(() -> userMapper.selectById(userId));
    }

    /** 更新最后登录时间（不写审计；供已识别租户的登录链路使用） */
    public void touchLastLogin(UUID userId) {
        User update = new User();
        update.setUserId(userId);
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);
    }
}
