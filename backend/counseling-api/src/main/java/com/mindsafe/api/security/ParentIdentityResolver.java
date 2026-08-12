package com.mindsafe.api.security;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.consent.ConsentWithdrawalService;
import com.mindsafe.service.parent.ParentService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 家长身份统一解析（doing/89 N-003 AC-89-04，2026-08-11）
 * <p>
 * 统一两条 token 语义的校验出口（原 ParentController 私有双实现：
 * resolveParentToken 旧链接四重校验 / resolveParentIdentity 新登录仅签名校验）：
 * - 统一 token 校验：签名有效 + 非 refresh/声纹凭证 + userType=parent
 * - 统一 withdrawn 拦截出口 ensureConsent()（数据端点一律调用；状态端点按业务宽松）
 * - 绑定校验 requireLinkedStudent()（防越权，与旧 requireLinkedStudent 语义一致）
 * Controller 不再维护私有解析实现——新端点无需记挂"传 true"（潜在旁路消除）。
 */
@Component
public class ParentIdentityResolver {

    private final JwtTokenProvider jwtTokenProvider;
    private final ParentService parentService;

    public ParentIdentityResolver(JwtTokenProvider jwtTokenProvider, ParentService parentService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.parentService = parentService;
    }

    /**
     * 统一 token 校验：Bearer 前缀剥离 + 单次 parse（签名/过期）+ refresh/声纹/家长类型三查。
     * <p>
     * 审计 F2：原 validateToken + isRefreshToken + isVoiceCredential + getUserType 4 次 parse
     * → parseOnce 1 次；BACK-008（doing/95，merge develop 保留）：tokenType 必须 parent_report——
     * 家长域 permitAll 靠本方法自校验，兼容 ACCESS 会重新放开业务 token 冒用家长域入口。
     */
    private JwtTokenProvider.ParsedToken parseParentToken(String authHeader) {
        // F20（doing/97）：Bearer 前缀校验收敛至 JwtTokenProvider.extractBearerToken（无前缀 → null → 401）
        String token = JwtTokenProvider.extractBearerToken(authHeader);
        if (token == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
        try {
            JwtTokenProvider.ParsedToken parsed = jwtTokenProvider.parseOrNull(token);
            if (parsed == null || parsed.tokenType() == TokenType.REFRESH
                    || parsed.tokenType() == TokenType.VOICE_CREDENTIAL
                    || parsed.tokenType() != TokenType.PARENT_REPORT
                    || !"parent".equals(parsed.userType())) {

                throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
            }
            return parsed;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
    }

    /**
     * 新登录语义解析（token sub=parentId）：仅签名校验，学生归属由 requireLinkedStudent 按绑定关系校验。
     * 原 ParentController.resolveParentIdentity 迁移。
     */
    public ParentIdentity resolveLoginIdentity(String authHeader) {
        JwtTokenProvider.ParsedToken parsed = parseParentToken(authHeader);
        return new ParentIdentity(parsed.userId(), parsed.tenantId());
    }

    /**
     * 旧链接语义解析（token sub=studentUserId）：四重校验 + 学生存在性 + withdrawn 拦截（410）。
     * 原 ParentController.resolveParentToken 迁移；租户绑定在调用方（send-code 不触 DB 场景不绑定）。
     */
    public ParentLinkIdentity resolveLegacyLink(String authHeader) {
        JwtTokenProvider.ParsedToken parsed = parseParentToken(authHeader);
        ParentLinkIdentity info = new ParentLinkIdentity(parsed.userId(), parsed.tenantId());

        // P1 审计修复：撤回同意后旧 token 失效。selectOne 受租户行隔离拦截，必须先绑定租户上下文
        TenantContextHolder.set(info.tenantId());
        try {
            User student = parentService.getStudent(info.tenantId(), info.studentUserId());
            if (student == null) {
                // token 指向不存在的学生 = 链接无效（保持原有语义）
                throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
            }
            if (ConsentWithdrawalService.STATUS_WITHDRAWN.equals(student.getStatus())) {
                // BUG-P-P03-01/P05-02：撤回是业务终态而非认证失败，须用 20011→410 而非 20001→401
                throw new BizException(ErrorCode.CONSENT_WITHDRAWN, "监护人同意已撤回，链接已失效");
            }
        } finally {
            TenantContextHolder.clear();
        }
        return info;
    }

    /**
     * 家长-学生绑定校验（防越权）。withdrawn 拦截统一出口（AC-89-04：新端点无需记挂传 true，
     * 数据端点一律调用本方法；状态端点不调用即宽松——BUG-P-P04-01 撤回后仍需可查）。
     */
    public void requireLinkedStudent(ParentIdentity identity, UUID studentUserId) {
        if (!parentService.isLinked(identity.parentId(), studentUserId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
        User student = parentService.getStudent(identity.tenantId(), studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
        if (ConsentWithdrawalService.STATUS_WITHDRAWN.equals(student.getStatus())) {
            // BUG-P-P03-01/P05-02：撤回是业务终态而非认证失败，须用 20011→410 而非 20001→401
            throw new BizException(ErrorCode.CONSENT_WITHDRAWN, "监护人同意已撤回，链接已失效");
        }
    }

    /**
     * 仅绑定校验（不拦截 withdrawn）——consent/status 状态端点专用（BUG-P-P04-01：
     * 撤回后家长仍需能查询"已撤回"状态，否则页面永远只显示"链接已失效"）。
     * 默认语义仍是 requireLinkedStudent（带 withdrawn 拦截），新端点无脑调用默认方法即可。
     */
    public void requireLinkedOnly(ParentIdentity identity, UUID studentUserId) {
        if (!parentService.isLinked(identity.parentId(), studentUserId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
    }

    /** 新登录语义身份（token sub=parentId） */
    public record ParentIdentity(UUID parentId, UUID tenantId) {}

    /** 旧链接流程语义身份（parent_report token 的 sub=studentUserId） */
    public record ParentLinkIdentity(UUID studentUserId, UUID tenantId) {}
}
