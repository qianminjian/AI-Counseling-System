package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.api.security.ParentIdentityResolver;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.consent.ConsentWithdrawalService;
import com.mindsafe.service.parent.ParentService;
import com.mindsafe.service.parent.WeeklyReportService;
import com.mindsafe.service.sms.PhoneVerificationService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 家长端 API（只读，token 鉴权）
 * <p>
 * 家长通过教师分享的链接访问，无需登录。
 * Token 由教师端生成，包含 studentUserId + tenantId + 7 天有效期。
 * <p>
 * <b>B1 修复</b>：parent_report token 不会被 JwtAuthenticationFilter 识别（其只处理 access token），
 * 但 parent_report token 包含 tenantId claim，本 Controller 在 ParentIdentityResolver 旧链接解析时直接从
 * token 提取 tenantId，并在每个端点的 DB 访问前通过 {@link TenantContextHolder#set(UUID)}
 * 显式绑定租户上下文，finally 中清除，确保租户行隔离拦截器不会 fail-fast。
 */
@RestController
@RequestMapping("/api/v1/parent")
public class ParentController {

    private final ParentIdentityResolver parentIdentityResolver;
    private final JwtTokenProvider jwtTokenProvider;
    private final ParentService parentService;
    private final WeeklyReportService weeklyReportService;
    private final ConsentWithdrawalService consentWithdrawalService;
    private final PhoneVerificationService phoneVerificationService;

    public ParentController(ParentIdentityResolver parentIdentityResolver,
                            JwtTokenProvider jwtTokenProvider,
                            ParentService parentService,
                            ConsentWithdrawalService consentWithdrawalService,
                            PhoneVerificationService phoneVerificationService, WeeklyReportService weeklyReportService) {
        this.parentIdentityResolver = parentIdentityResolver;
        this.jwtTokenProvider = jwtTokenProvider;
        this.parentService = parentService;
        this.weeklyReportService = weeklyReportService;
        this.consentWithdrawalService = consentWithdrawalService;
        this.phoneVerificationService = phoneVerificationService;
    }

    /**
     * 获取学生情绪周报（家长只读）
     * Header: Authorization: Bearer <parent_token>
     * Query: studentUserId（前端从 children 列表传入，BUG-P-BASE-04：
     * 新登录 token sub=parentId，不再从 token 推断学生）
     */
    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> getWeeklyReport(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam UUID studentUserId) {
        ParentIdentityResolver.ParentIdentity identity = parentIdentityResolver.resolveLoginIdentity(authHeader);
        TenantContextHolder.set(identity.tenantId());
        try {
            parentIdentityResolver.requireLinkedStudent(identity, studentUserId);
            return doGetWeeklyReport(identity.tenantId(), studentUserId);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ApiResponse<Map<String, Object>> doGetWeeklyReport(UUID tenantId, UUID studentUserId) {
        // doing/89 N-003 步骤 5：聚合下沉 WeeklyReportService（Controller 仅编排，AC-89-07）
        Map<String, Object> report = weeklyReportService.generate(tenantId, studentUserId);
        if (report == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }
        return ApiResponse.ok(report);
    }

    /**
     * 家长撤回同意（AUTH-032，PIPL §47）
     * <p>
     * 监护人随时可撤回：冻结学生账号 + 删除心理画像 + 留痕。操作不可逆（需重新授权）。
     * Header: Authorization: Bearer <parent_token>
     */
    @PostMapping("/consent/withdraw")
    public ApiResponse<Map<String, Object>> withdrawConsent(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam UUID studentUserId) {
        ParentIdentityResolver.ParentIdentity identity = parentIdentityResolver.resolveLoginIdentity(authHeader);
        TenantContextHolder.set(identity.tenantId());
        try {
            parentIdentityResolver.requireLinkedStudent(identity, studentUserId);
            return doWithdrawConsent(identity.tenantId(), studentUserId);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ApiResponse<Map<String, Object>> doWithdrawConsent(UUID tenantId, UUID studentUserId) {
        // T4 批次C：查询下沉 ParentService（租户 + 学生双条件）
        User student = parentService.getStudent(tenantId, studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }

        consentWithdrawalService.withdrawConsent(tenantId, studentUserId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentUserId", studentUserId);
        result.put("status", "withdrawn");
        result.put("message", "已撤回同意，孩子账号已冻结，心理画像已删除。如需恢复请联系学校重新授权。");
        result.put("withdrawnAt", Instant.now().toString());
        return ApiResponse.ok(result);
    }

    /**
     * 查询监护人授权状态（BUG-P-P04-01：同意管理页展示状态/时间/版本）。
     * <p>
     * 状态端点专用：仅绑定校验（requireLinkedOnly，不拦截 withdrawn）——
     * 撤回后家长仍需能查询到"已撤回"状态，否则页面永远只显示"链接已失效"。
     */
    @GetMapping("/consent/status")
    public ApiResponse<Map<String, Object>> getConsentStatus(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam UUID studentUserId) {
        ParentIdentityResolver.ParentIdentity identity = parentIdentityResolver.resolveLoginIdentity(authHeader);
        TenantContextHolder.set(identity.tenantId());
        try {
            parentIdentityResolver.requireLinkedOnly(identity, studentUserId);
            return ApiResponse.ok(consentWithdrawalService.getConsentStatus(identity.tenantId(), studentUserId));
        } finally {
            TenantContextHolder.clear();
        }
    }

    // ===== AUTH-013 手机验证 =====

    /**
     * 发送手机验证码（家长打开链接后第一步）
     * <p>
     * Body: { "phone": "13812345678" }
     * Header: Authorization: Bearer <parent_token>（教师生成的初始 token）
     */
    @PostMapping("/send-code")
    public ApiResponse<Map<String, String>> sendVerificationCode(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> body) {
        // 验证初始 token 有效（B1：sendCode 不触 DB，仅需 token 校验，不需租户上下文）
        parentIdentityResolver.resolveLegacyLink(authHeader);

        String phone = body.get("phone");
        phoneVerificationService.sendCode(phone, "家长身份验证");

        return ApiResponse.ok(Map.of("status", "sent", "message", "验证码已发送，5 分钟内有效"));
    }

    /**
     * 验证手机并签发正式 7 天 Token（家长第二步）
     * <p>
     * Body: { "phone": "13812345678", "code": "123456" }
     * Header: Authorization: Bearer <parent_token>
     */
    @PostMapping("/verify-phone")
    public ApiResponse<Map<String, Object>> verifyPhone(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> body) {
        ParentIdentityResolver.ParentLinkIdentity info = parentIdentityResolver.resolveLegacyLink(authHeader);
        TenantContextHolder.set(info.tenantId());
        try {
            return doVerifyPhone(info, body);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ApiResponse<Map<String, Object>> doVerifyPhone(ParentIdentityResolver.ParentLinkIdentity info, Map<String, String> body) {
        UUID studentUserId = info.studentUserId();

        String phone = body.get("phone");
        String code = body.get("code");

        boolean verified = phoneVerificationService.verifyCode(phone, code);
        if (!verified) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "验证码错误或已过期");
        }

        // 验证通过，签发正式 7 天 token（SEC-006：独立 parent_report tokenType + 7d TTL，兑现有效期承诺）
        User student = parentService.getStudent(info.tenantId(), studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }

        String formalToken = jwtTokenProvider.generateParentReportToken(studentUserId, info.tenantId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", formalToken);
        result.put("expiresIn", "7天");
        result.put("phone", phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4));
        result.put("message", "验证成功，请保存此链接");
        return ApiResponse.ok(result);
    }
}
