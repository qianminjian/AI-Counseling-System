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
        //    F9：候选立即快照为 LoginCandidate（仅含认证所需字段，含 passwordHash 供凭据匹配），实体不再下传
        List<LoginCandidate> candidates = authUserService.findLoginCandidates(username).stream()
                .map(LoginCandidate::from)
                .toList();
        if (candidates.size() > 1) {
            log.warn("登录拒绝：昵称重复无法唯一定位账号, username={}, matches={}", username, candidates.size());
            lockoutService.recordFailure(username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        LoginCandidate candidate = candidates.isEmpty() ? null : candidates.get(0);

        // 3. F-1：撤回同意冻结账号（withdrawn）→ 专属提示（PIPL §47，需重新授权恢复）
        if (candidate != null && User.STATUS_WITHDRAWN.equals(candidate.status())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已冻结，请联系家长或学校重新授权");
        }

        // 4. 凭据匹配（失败记锁定）
        if (candidate == null || candidate.passwordHash() == null
                || !passwordEncoder.matches(rawPassword, candidate.passwordHash())) {
            lockoutService.recordFailure(username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 5. SEC-004：租户状态门禁
        guardTenantLogin(candidate);

        // 6. 登录成功，清除失败计数 + 留痕（T4 批次B：下沉 AuthUserService，租户上下文绑定在 Service 内；
        //    LOGIN 审计由 recordLoginSuccess 承担，issueLoginSession 传 null 跳过避免双重审计）
        lockoutService.clearFailures(username);
        authUserService.recordLoginSuccess(candidate.tenantId(), candidate.userId());

        // 7. 签发
        return issueLoginSession(candidate, null);
    }

    /**
     * 租户状态门禁（SEC-004）：suspended/archived 租户禁止登录。
     * 统一单点——此前 pin/voice 路径缺失此门禁（S-001 不对称修正）。
     */
    public void guardTenantLogin(LoginCandidate candidate) {
        if (!tenantAccessGuard.isLoginAllowed(candidate.tenantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "学校账号暂时不可用，请联系管理员");
        }
    }

    /**
     * 签发双 token + 登录审计（统一单点；此前 6 端点各自重复签发三连）。
     * auditAction=null 时跳过审计（密码登录路径审计已由 AuthUserService.recordLoginSuccess 承担，
     * 避免双重 LOGIN 留痕；pin/voice 等路径传入动作名）。
     * 审计需绑定真实租户上下文提交（@Async 经 TaskDecorator 继承，否则 fail-fast 拒绝写入）。
     */
    public LoginSession issueLoginSession(LoginCandidate candidate, String auditAction) {
        // BACK-008：家长域接口强制 PARENT_REPORT 类型——家长登录必须签 parent 专用 token，
        // 否则 ParentIdentityResolver 拒 401（2026-08-13 遍历回归：/parent/report 全 401）
        String token = "parent".equals(candidate.userType())
                ? businessAuthProvider.issueParentAccessToken(candidate.userId(), candidate.tenantId())
                : businessAuthProvider.issueAccessToken(
                        candidate.userId(), candidate.userType(), candidate.tenantId());
        String refreshToken = businessAuthProvider.issueRefreshToken(
                candidate.userId(), candidate.userType(), candidate.tenantId());
        if (auditAction != null) {
            TenantContextHolder.set(candidate.tenantId());
            try {
                auditLogService.log(candidate.tenantId(), candidate.userId(), auditAction, "user", candidate.userId(), null);
            } finally {
                TenantContextHolder.clear();
            }
        }
        // 无密码用户（如试用账号）不参与密码过期策略判定，仅取 DB 标志（S-001 审查修正：
        // 否则 passwordChangedAt=null 时 isExpired 恒 true → PIN 登录误报强制改密）
        boolean mustChange = Boolean.TRUE.equals(candidate.mustChangePassword())
                || (candidate.passwordHash() != null
                && passwordPolicyService.isExpired(candidate.passwordChangedAt()));
        return new LoginSession(token, refreshToken, candidate.userId(), candidate.pseudonym(),
                candidate.userType(), candidate.gradeCode(), candidate.classCode(), mustChange);
    }
}
