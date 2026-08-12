package com.mindsafe.api.auth;

import com.mindsafe.api.security.BusinessAuthProvider;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.auth.AuthUserService;
import com.mindsafe.service.auth.LoginLockoutService;
import com.mindsafe.service.auth.PasswordPolicyService;
import com.mindsafe.service.auth.TenantAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 登录编排单点（S-001，doing/93）。
 * <p>
 * "一次登录尝试"的固定顺序约束（锁定→候选定位→状态→凭据→租户门禁→清计数→留痕→签发）
 * 收敛于此：此前 login/pinLogin/voiceLogin 三路径各写不同子集（家长路径曾缺租户门禁、
 * PIN 路径缺服务内门禁），不对称只能靠对照源码发现；token 签发三连在 6 端点重复。
 * <p>
 * 各认证入口只保留差异部分（如何定位账号/如何验凭据），共享编排与签发在此单点演化。
 */
@Service
public class LoginOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LoginOrchestrator.class);

    private final LoginLockoutService lockoutService;
    private final AuthUserService authUserService;
    private final PasswordEncoder passwordEncoder;
    private final TenantAccessGuard tenantAccessGuard;
    private final BusinessAuthProvider businessAuthProvider;
    private final PasswordPolicyService passwordPolicyService;
    private final AuditLogService auditLogService;

    public LoginOrchestrator(LoginLockoutService lockoutService,
                             AuthUserService authUserService,
                             PasswordEncoder passwordEncoder,
                             TenantAccessGuard tenantAccessGuard,
                             BusinessAuthProvider businessAuthProvider,
                             PasswordPolicyService passwordPolicyService,
                             AuditLogService auditLogService) {
        this.lockoutService = lockoutService;
        this.authUserService = authUserService;
        this.passwordEncoder = passwordEncoder;
        this.tenantAccessGuard = tenantAccessGuard;
        this.businessAuthProvider = businessAuthProvider;
        this.passwordPolicyService = passwordPolicyService;
        this.auditLogService = auditLogService;
    }

    /** 登录会话产物（双 token + 用户展示信息） */
    public record LoginSession(String accessToken, String refreshToken, UUID userId,
                               String pseudonym, String userType,
                               String gradeCode, String classCode, boolean mustChangePassword) {
    }

    /**
     * 密码登录完整编排（通用登录 8 步链）：
     * 锁定检查 → 候选定位（重名拒绝）→ withdrawn 冻结拒绝 → 密码匹配 → 租户门禁 → 清计数 → 留痕 → 签发。
     */
    public LoginSession loginWithPassword(String username, String rawPassword) {
        // 1. 登录失败锁定检查
        lockoutService.checkLockout(username);

        // 2. 前置认证链路（无 JWT，跨租户按昵称查用户）：候选查询下沉 AuthUserService（系统作用域在 Service 内声明）
        //    SEC-003：昵称无全局唯一约束，重名时拒绝登录（防 LIMIT 1 随机命中他人账号）
        List<User> candidates = authUserService.findLoginCandidates(username);
        if (candidates.size() > 1) {
            log.warn("登录拒绝：昵称重复无法唯一定位账号, username={}, matches={}", username, candidates.size());
            lockoutService.recordFailure(username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        User user = candidates.isEmpty() ? null : candidates.get(0);

        // 3. F-1：撤回同意冻结账号（withdrawn）→ 专属提示（PIPL §47，需重新授权恢复）
        if (user != null && User.STATUS_WITHDRAWN.equals(user.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已冻结，请联系家长或学校重新授权");
        }

        // 4. 凭据匹配（失败记锁定）
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            lockoutService.recordFailure(username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 5. SEC-004：租户状态门禁
        guardTenantLogin(user);

        // 6. 登录成功，清除失败计数 + 留痕（T4 批次B：下沉 AuthUserService，租户上下文绑定在 Service 内）
        lockoutService.clearFailures(username);
        authUserService.recordLoginSuccess(user.getTenantId(), user.getUserId());

        // 7. 签发
        return issueLoginSession(user, "LOGIN");
    }

    /**
     * 租户状态门禁（SEC-004）：suspended/archived 租户禁止登录。
     * 统一单点——此前 pin/voice 路径缺失此门禁（S-001 不对称修正）。
     */
    public void guardTenantLogin(User user) {
        if (!tenantAccessGuard.isLoginAllowed(user.getTenantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "学校账号暂时不可用，请联系管理员");
        }
    }

    /**
     * 签发双 token + 登录审计（统一单点；此前 6 端点各自重复签发三连）。
     * 审计需绑定真实租户上下文提交（@Async 经 TaskDecorator 继承，否则 fail-fast 拒绝写入）。
     */
    public LoginSession issueLoginSession(User user, String auditAction) {
        String token = businessAuthProvider.issueAccessToken(
                user.getUserId(), user.getUserType(), user.getTenantId());
        String refreshToken = businessAuthProvider.issueRefreshToken(
                user.getUserId(), user.getUserType(), user.getTenantId());
        TenantContextHolder.set(user.getTenantId());
        try {
            auditLogService.log(user.getTenantId(), user.getUserId(), auditAction, "user", user.getUserId(), null);
        } finally {
            TenantContextHolder.clear();
        }
        boolean mustChange = Boolean.TRUE.equals(user.getMustChangePassword())
                || passwordPolicyService.isExpired(user.getPasswordChangedAt());
        return new LoginSession(token, refreshToken, user.getUserId(), user.getPseudonym(),
                user.getUserType(), user.getGradeCode(), user.getClassCode(), mustChange);
    }
}
