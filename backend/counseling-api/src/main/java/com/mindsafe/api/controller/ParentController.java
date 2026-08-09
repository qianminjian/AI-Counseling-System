package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.consent.ConsentWithdrawalService;
import com.mindsafe.service.parent.ParentService;
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
 * 但 parent_report token 包含 tenantId claim，本 Controller 在 resolveParentToken 时直接从
 * token 提取 tenantId，并在每个端点的 DB 访问前通过 {@link TenantContextHolder#set(UUID)}
 * 显式绑定租户上下文，finally 中清除，确保租户行隔离拦截器不会 fail-fast。
 */
@RestController
@RequestMapping("/api/v1/parent")
public class ParentController {

    private final JwtTokenProvider jwtTokenProvider;
    private final ParentService parentService;
    private final ConsentWithdrawalService consentWithdrawalService;
    private final PhoneVerificationService phoneVerificationService;

    public ParentController(JwtTokenProvider jwtTokenProvider,
                            ParentService parentService,
                            ConsentWithdrawalService consentWithdrawalService,
                            PhoneVerificationService phoneVerificationService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.parentService = parentService;
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
        ParentIdentity identity = resolveParentIdentity(authHeader);
        TenantContextHolder.set(identity.tenantId());
        try {
            requireLinkedStudent(identity, studentUserId, true);
            return doGetWeeklyReport(identity.tenantId(), studentUserId);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ApiResponse<Map<String, Object>> doGetWeeklyReport(UUID tenantId, UUID studentUserId) {
        // T4 批次C：查询下沉 ParentService（租户 + 学生双条件）
        User student = parentService.getStudent(tenantId, studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }

        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);

        // 近 7 天会话
        List<CounselingSession> sessions = parentService.getRecentSessions(tenantId, studentUserId, weekAgo);

        // 近 7 天情绪标签统计
        List<MessageSummary> studentMessages =
                parentService.getRecentStudentMessages(tenantId, studentUserId, weekAgo);

        // 情绪分布
        Map<String, Long> emotionDist = studentMessages.stream()
                .filter(m -> m.getEmotionLabel() != null && !m.getEmotionLabel().isBlank())
                .collect(Collectors.groupingBy(MessageSummary::getEmotionLabel, Collectors.counting()));

        // 最高风险等级
        int maxRisk = sessions.stream()
                .mapToInt(s -> s.getRiskLevelSnapshot() != null ? s.getRiskLevelSnapshot() : 0)
                .max().orElse(0);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("studentNickname", student.getPseudonym());
        report.put("gradeCode", student.getGradeCode());
        report.put("classCode", student.getClassCode());
        report.put("weekStart", weekAgo.toString());
        report.put("sessionCount", sessions.size());
        report.put("totalTurns", sessions.stream().mapToInt(s -> s.getTurnCount() != null ? s.getTurnCount() : 0).sum());
        report.put("emotionDistribution", emotionDist);
        report.put("maxRiskLevel", maxRisk);
        report.put("riskLabel", switch (maxRisk) {
            case 3 -> "需关注";
            case 2 -> "轻度波动";
            case 1 -> "平稳";
            default -> "良好";
        });
        report.put("generatedAt", Instant.now().toString());

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
        ParentIdentity identity = resolveParentIdentity(authHeader);
        TenantContextHolder.set(identity.tenantId());
        try {
            requireLinkedStudent(identity, studentUserId, true);
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
     * 解析 parent_report token，返回 studentUserId + tenantId（B1 修复：从 token 提取 tenantId
     * 供端点绑定 TenantContextHolder，避免 MyBatis-Plus 租户行隔离 fail-fast）。
     * <p>
     * 四重校验：签名有效 + 非 refresh/声纹凭证 + userType 必须为 parent +
     * 学生账号未被撤回同意（P1 审计修复：status=withdrawn 后旧 token 立即失效）。
     * 学生自持的 access token（userType=student）无法调用家长接口（如撤回监护人同意）。
     */
    private ParentTokenInfo resolveParentToken(String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        try {
            if (!jwtTokenProvider.validateToken(token)
                    || jwtTokenProvider.isRefreshToken(token)
                    || jwtTokenProvider.isVoiceCredential(token)
                    || !"parent".equals(jwtTokenProvider.getUserType(token))) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
            }
            ParentTokenInfo info = new ParentTokenInfo(
                    jwtTokenProvider.getUserId(token),
                    jwtTokenProvider.getTenantId(token));

            // P1 审计修复：撤回同意后旧 token 失效。selectOne 受租户行隔离拦截，必须先绑定租户上下文
            // T4 批次C：查询下沉 ParentService（租户 + 学生双条件）
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
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
    }

    /**
     * 查询监护人授权状态（BUG-P-P04-01：同意管理页展示 状态/时间/版本）。
     * <p>
     * 与其它端点不同：本端点使用宽松 token 校验（不拦截 withdrawn 状态）——
     * 撤回后家长仍需能查询到"已撤回"状态，否则页面永远只显示"链接已失效"。
     */
    @GetMapping("/consent/status")
    public ApiResponse<Map<String, Object>> getConsentStatus(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam UUID studentUserId) {
        ParentIdentity identity = resolveParentIdentity(authHeader);
        TenantContextHolder.set(identity.tenantId());
        try {
            // 宽松校验：撤回后仍需可查（checkWithdrawn=false）
            requireLinkedStudent(identity, studentUserId, false);
            return ApiResponse.ok(consentWithdrawalService.getConsentStatus(identity.tenantId(), studentUserId));
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 解析家长身份（BUG-P-BASE-04：新登录 token sub=parentId，
     * 仅校验签名/类型，学生归属由 requireLinkedStudent 按绑定关系校验）。
     */
    private ParentIdentity resolveParentIdentity(String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        try {
            if (!jwtTokenProvider.validateToken(token)
                    || jwtTokenProvider.isRefreshToken(token)
                    || jwtTokenProvider.isVoiceCredential(token)
                    || !"parent".equals(jwtTokenProvider.getUserType(token))) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
            }
            return new ParentIdentity(
                    jwtTokenProvider.getUserId(token),
                    jwtTokenProvider.getTenantId(token));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
    }

    /**
     * 校验家长-学生绑定关系（防越权）+ 可选撤回拦截。
     *
     * @param checkWithdrawn true=同意已撤回时抛 410（数据端点）；false=仅校验绑定（状态端点）
     */
    private void requireLinkedStudent(ParentIdentity identity, UUID studentUserId, boolean checkWithdrawn) {
        if (!parentService.isLinked(identity.parentId(), studentUserId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
        User student = parentService.getStudent(identity.tenantId(), studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }
        if (checkWithdrawn && ConsentWithdrawalService.STATUS_WITHDRAWN.equals(student.getStatus())) {
            // BUG-P-P03-01/P05-02：撤回是业务终态而非认证失败，须用 20011→410 而非 20001→401
            throw new BizException(ErrorCode.CONSENT_WITHDRAWN, "监护人同意已撤回，链接已失效");
        }
    }

    private record ParentIdentity(UUID parentId, UUID tenantId) {}

    /** 旧链接流程（send-code/verify-phone）：parent_report token 的 sub=studentUserId */
    private record ParentTokenInfo(UUID studentUserId, UUID tenantId) {}

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
        resolveParentToken(authHeader);

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
        ParentTokenInfo info = resolveParentToken(authHeader);
        TenantContextHolder.set(info.tenantId());
        try {
            return doVerifyPhone(info, body);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ApiResponse<Map<String, Object>> doVerifyPhone(ParentTokenInfo info, Map<String, String> body) {
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
