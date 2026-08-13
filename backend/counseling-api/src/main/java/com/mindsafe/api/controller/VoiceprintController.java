package com.mindsafe.api.controller;

import com.mindsafe.api.ratelimit.RateLimiter;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.voiceprint.VoiceprintDomain;
import com.mindsafe.service.voiceprint.VoiceprintEnrollService;
import com.mindsafe.service.voiceprint.VoiceprintLoginService;
import com.mindsafe.service.voiceprint.VoiceprintVerifyService;
import com.mindsafe.service.voiceprint.VoiceprintVerifyService.VerifyOutcome;
import com.mindsafe.service.device.DeviceVoiceprintService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * 声纹识别 API（双模式 local/remote）：GET /config 公开返回模式；POST /enroll 登录录入（remote）；
 * POST /verify 公开比对（该租户内，通过则签发双 token）。隐私：仅存 256-dim 特征向量。
 * AUD-001：verify 强制租户维度 + 指纹级限流；域逻辑已下沉 DC-006（域服务/纯函数）。
 */
@RestController
@RequestMapping("/api/v1/voiceprint")
public class VoiceprintController {

    private static final Logger log = LoggerFactory.getLogger(VoiceprintController.class);

    private final VoiceprintVerifyService verifyService;
    private final VoiceprintEnrollService enrollService;
    private final VoiceprintLoginService voiceprintLoginService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogService auditLogService;
    private final RateLimiter rateLimiter;
    private final DeviceVoiceprintService deviceVoiceprintService;

    /** 公开端点按 IP 限流：每分钟最多 10 次声纹验证尝试（防暴力探测） */
    private static final int VERIFY_MAX_PER_MINUTE = 10;
    /** AUD-001：同一 embedding 指纹每分钟最多 5 次（防抓包重放；换 IP 无法绕过） */
    private static final int VERIFY_FP_MAX_PER_MINUTE = 5;

    @Value("${mindsafe.voiceprint.mode:local}")
    private String voiceprintMode;

    public VoiceprintController(VoiceprintVerifyService verifyService,
                                VoiceprintEnrollService enrollService,
                                VoiceprintLoginService voiceprintLoginService,
                                JwtTokenProvider jwtTokenProvider,
                                AuditLogService auditLogService,
                                RateLimiter rateLimiter,
                                DeviceVoiceprintService deviceVoiceprintService) {
        this.verifyService = verifyService;
        this.enrollService = enrollService;
        this.voiceprintLoginService = voiceprintLoginService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.auditLogService = auditLogService;
        this.rateLimiter = rateLimiter;
        this.deviceVoiceprintService = deviceVoiceprintService;
    }

    /** 获取声纹配置（公开）：前端据此决定走 local 还是 remote 流程 */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(Map.of(
                "mode", voiceprintMode,
                "privacyNote", "remote".equals(voiceprintMode)
                        ? "声音特征向量将加密传输到服务器进行比对，原始声音不会上传和保存"
                        : "声音信息只保存在这台设备上，不会上传到任何服务器"
        ));
    }

    /** 声纹录入（remote 模式，需已登录）：前端提取 embedding 后传服务端存储 */
    @PostMapping("/enroll")
    public ApiResponse<Map<String, Object>> enroll(
            @Valid @RequestBody EnrollRequest request, Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        UUID userId = ctx.userId();
        UUID tenantId = ctx.tenantId();

        // 域服务：删旧模板 → 写入新模板（限 maxTemplates）
        int count = enrollService.enroll(userId, tenantId, request.embeddings());
        auditLogService.log(tenantId, userId, "VOICEPRINT_ENROLL_REMOTE", "user", userId, null);

        // CFG-006（doing/84 §四.4）：设备端声纹录入任务完成置位（taskId 可选，无任务时不影响既有流程）
        // B-03（doing/98）：本处是任务 COMPLETED 的唯一真实驱动——UPLOADED 后停留等待落库成功置位
        if (request.taskId() != null && !request.taskId().isBlank()) {
            deviceVoiceprintService.complete(request.taskId());
        }

        // AUD-001：响应携带 tenantId——前端 verify 需携带租户维度，由服务端签发避免前端伪造归属
        return ApiResponse.ok(Map.of("enrolled", count, "mode", "remote",
                "tenantId", tenantId.toString()));
    }

    /**
     * 声纹验证登录（remote，公开端点）：比对通过直接签发双 token。
     * AUD-001：必填 tenantId 收窄比对范围；即使枚举 tenantId 也需先持有目标用户真实 embedding。
     */
    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verify(@Valid @RequestBody VerifyRequest request,
                                                   HttpServletRequest httpRequest) {
        List<List<Double>> inputEmbeddings = request.embeddings();
        if (inputEmbeddings.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "embedding 不能为空");
        }

        // SEC-007：公开端点按 IP 限流，防 embedding 暴力探测
        String clientIp = VoiceprintDomain.resolveClientIp(httpRequest);
        if (!rateLimiter.tryAcquire(clientIp, "voiceprint_verify",
                VERIFY_MAX_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new BizException(ErrorCode.RATE_LIMITED, "尝试太频繁了，请稍等一下再试");
        }

        // AUD-001：embedding 指纹级限流（SHA-256 作 key）——抓包重放换 IP 也无法绕过
        String fingerprint = VoiceprintDomain.fingerprint(inputEmbeddings);
        if (!rateLimiter.tryAcquire(fingerprint, "voiceprint_verify_fp",
                VERIFY_FP_MAX_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new BizException(ErrorCode.RATE_LIMITED, "尝试太频繁了，请稍等一下再试");
        }

        // 公开端点无 JWT → 系统作用域 + 显式 tenant_id 条件（M1-003）：租户隔离由查询条件保证
        return TenantContextHolder.callAsSystem(() -> doVerify(request.tenantId(), inputEmbeddings));
    }

    private ApiResponse<Map<String, Object>> doVerify(UUID tenantId, List<List<Double>> inputEmbeddings) {
        // 域服务：租户过滤（查询条件 + 防御性二次过滤）+ 分组 + 1:N 比对 + 阈值判定（DC-006）
        VerifyOutcome outcome = verifyService.verify(tenantId, inputEmbeddings);

        if (!outcome.matched()) {
            // P0-3：失败审计——有候选但未达标时留痕（暴力探测可追踪）；无候选（库空/全损坏）静默
            if (outcome.hasCandidate()) {
                auditLogService.log(outcome.tenantId(), outcome.userId(), "VOICEPRINT_VERIFY_FAILED",
                        "user", outcome.userId(), null);
            }
            return ApiResponse.ok(Map.of("matched", false));
        }

        // 匹配成功：查用户 + 签发 token（T4 批次B：账号状态 + 租户门禁判定随查询下沉 Service）
        User user = voiceprintLoginService.findLoginAllowedUser(outcome.userId());
        if (user == null) {
            return ApiResponse.ok(Map.of("matched", false));
        }

        // 更新最后登录时间（T4 批次B：下沉 Service）
        voiceprintLoginService.touchLastLogin(user.getUserId());

        String token = jwtTokenProvider.generateToken(
                user.getUserId(), user.getUserType(), user.getTenantId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                user.getUserId(), user.getUserType(), user.getTenantId());
        auditLogService.log(user.getTenantId(), user.getUserId(), "VOICEPRINT_LOGIN_REMOTE",
                "user", user.getUserId(), null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", true);
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("userId", user.getUserId());
        result.put("displayName", user.getPseudonym());
        result.put("userType", user.getUserType());
        return ApiResponse.ok(result);
    }

    // ===== Request DTOs =====

    public record EnrollRequest(
            @NotEmpty(message = "embeddings 不能为空")
            List<List<Double>> embeddings,
            // CFG-006：无屏终端声纹录入任务 ID（可选，设备端编排联动）
            String taskId
    ) {}

    public record VerifyRequest(
            @NotNull(message = "tenantId 不能为空")
            UUID tenantId,
            @NotEmpty(message = "embeddings 不能为空")
            List<List<Double>> embeddings
    ) {}
}
