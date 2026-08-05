package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.consent.ConsentWithdrawalService;
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
    private final UserMapper userMapper;
    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final ConsentWithdrawalService consentWithdrawalService;
    private final PhoneVerificationService phoneVerificationService;

    public ParentController(JwtTokenProvider jwtTokenProvider,
                            UserMapper userMapper,
                            CounselingSessionMapper sessionMapper,
                            MessageSummaryMapper messageSummaryMapper,
                            ConsentWithdrawalService consentWithdrawalService,
                            PhoneVerificationService phoneVerificationService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.consentWithdrawalService = consentWithdrawalService;
        this.phoneVerificationService = phoneVerificationService;
    }

    /**
     * 获取学生情绪周报（家长只读）
     * Header: Authorization: Bearer <parent_token>
     */
    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> getWeeklyReport(@RequestHeader("Authorization") String authHeader) {
        ParentTokenInfo info = resolveParentToken(authHeader);
        TenantContextHolder.set(info.tenantId());
        try {
            return doGetWeeklyReport(info);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ApiResponse<Map<String, Object>> doGetWeeklyReport(ParentTokenInfo info) {
        UUID studentUserId = info.studentUserId();
        User student = userMapper.selectById(studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }

        UUID tenantId = student.getTenantId();
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);

        // 近 7 天会话
        List<CounselingSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .ge(CounselingSession::getStartedAt, weekAgo)
                        .orderByDesc(CounselingSession::getStartedAt)
        );

        // 近 7 天情绪标签统计
        List<MessageSummary> studentMessages = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getStudentUserId, studentUserId)
                        .eq(MessageSummary::getSenderType, User.USER_TYPE_STUDENT)
                        .ge(MessageSummary::getCreatedAt, weekAgo)
        );

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
    public ApiResponse<Map<String, Object>> withdrawConsent(@RequestHeader("Authorization") String authHeader) {
        ParentTokenInfo info = resolveParentToken(authHeader);
        TenantContextHolder.set(info.tenantId());
        try {
            return doWithdrawConsent(info);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ApiResponse<Map<String, Object>> doWithdrawConsent(ParentTokenInfo info) {
        UUID studentUserId = info.studentUserId();
        User student = userMapper.selectById(studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }

        consentWithdrawalService.withdrawConsent(info.tenantId(), studentUserId);

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

            // P1 审计修复：撤回同意后旧 token 失效。selectById 受租户行隔离拦截，必须先绑定租户上下文
            TenantContextHolder.set(info.tenantId());
            try {
                User student = userMapper.selectById(info.studentUserId());
                if (student == null
                        || ConsentWithdrawalService.STATUS_WITHDRAWN.equals(student.getStatus())) {
                    throw new BizException(ErrorCode.UNAUTHORIZED, "监护人同意已撤回，链接已失效");
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

    private record ParentTokenInfo(UUID studentUserId, UUID tenantId) {}

    /** @deprecated 由 {@link #resolveParentToken} 替代，保留兼容性注释 */
    private UUID resolveStudentUserId(String authHeader) {
        return resolveParentToken(authHeader).studentUserId();
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
        User student = userMapper.selectById(studentUserId);
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
