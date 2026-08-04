package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.api.ratelimit.RateLimiter;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.entity.VoiceprintEmbedding;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.auth.TenantAccessGuard;
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
import java.time.Instant;
import java.util.*;

/**
 * 声纹识别 API（双模式：local / remote）
 * <p>
 * - GET  /config  — 公开：返回当前声纹模式（前端据此切换 local/remote 流程）
 * - POST /enroll  — 需登录：存储 embedding 向量到服务端（remote 模式录入）
 * - POST /verify  — 公开：接收 embedding 向量，服务端比对，通过则签发双 token（remote 模式登录）
 * <p>
 * 隐私：仅存 256-dim 特征向量（不可逆向还原音频），不存原始声音。
 */
@RestController
@RequestMapping("/api/v1/voiceprint")
public class VoiceprintController {

    private static final Logger log = LoggerFactory.getLogger(VoiceprintController.class);

    private final VoiceprintEmbeddingMapper embeddingMapper;
    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final TenantAccessGuard tenantAccessGuard;

    /** 公开端点按 IP 限流：每分钟最多 10 次声纹验证尝试（防暴力探测） */
    private static final int VERIFY_MAX_PER_MINUTE = 10;

    @Value("${mindsafe.voiceprint.mode:local}")
    private String voiceprintMode;

    @Value("${mindsafe.voiceprint.verify-threshold:0.55}")
    private double verifyThreshold;

    @Value("${mindsafe.voiceprint.max-templates:8}")
    private int maxTemplates;

    public VoiceprintController(VoiceprintEmbeddingMapper embeddingMapper,
                                UserMapper userMapper,
                                JwtTokenProvider jwtTokenProvider,
                                AuditLogService auditLogService,
                                ObjectMapper objectMapper,
                                RateLimiter rateLimiter,
                                TenantAccessGuard tenantAccessGuard) {
        this.embeddingMapper = embeddingMapper;
        this.userMapper = userMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * 获取声纹配置（公开，无需登录）
     * 前端启动时调用，决定走 local 还是 remote 流程
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(Map.of(
                "mode", voiceprintMode,
                "privacyNote", "remote".equals(voiceprintMode)
                        ? "声音特征向量将加密传输到服务器进行比对，原始声音不会上传和保存"
                        : "声音信息只保存在这台设备上，不会上传到任何服务器"
        ));
    }

    /**
     * 声纹录入（remote 模式，需已登录）
     * 前端提取 embedding 后传到服务端存储
     */
    @PostMapping("/enroll")
    public ApiResponse<Map<String, Object>> enroll(
            @Valid @RequestBody EnrollRequest request, Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        UUID userId = ctx.userId();
        UUID tenantId = ctx.tenantId();

        // 删除旧模板（重新录入）
        embeddingMapper.delete(new LambdaQueryWrapper<VoiceprintEmbedding>()
                .eq(VoiceprintEmbedding::getUserId, userId));

        // 写入新模板（限制最大数量）
        List<List<Double>> embeddings = request.embeddings();
        int count = Math.min(embeddings.size(), maxTemplates);
        for (int i = 0; i < count; i++) {
            VoiceprintEmbedding entity = new VoiceprintEmbedding();
            entity.setUserId(userId);
            entity.setTenantId(tenantId);
            entity.setEmbedding(toJson(embeddings.get(i)));
            entity.setSampleIndex(i);
            entity.setCreatedAt(Instant.now());
            embeddingMapper.insert(entity);
        }

        auditLogService.log(tenantId, userId, "VOICEPRINT_ENROLL_REMOTE", "user", userId, null);

        return ApiResponse.ok(Map.of("enrolled", count, "mode", "remote"));
    }

    /**
     * 声纹验证登录（remote 模式，公开端点）
     * 前端提取 embedding 后传到服务端比对，通过则直接签发双 token
     */
    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verify(@Valid @RequestBody VerifyRequest request,
                                                   HttpServletRequest httpRequest) {
        List<List<Double>> inputEmbeddings = request.embeddings();
        if (inputEmbeddings.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "embedding 不能为空");
        }

        // SEC-007：公开端点按 IP 限流，防 embedding 暴力探测
        String clientIp = resolveClientIp(httpRequest);
        if (!rateLimiter.tryAcquire(clientIp, "voiceprint_verify",
                VERIFY_MAX_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new BizException(ErrorCode.RATE_LIMITED, "尝试太频繁了，请稍等一下再试");
        }

        // 声纹验证是公开端点（permitAll），无 JWT → 无租户上下文
        // 跨用户 1:N 比对需系统作用域（M1-003）
        return TenantContextHolder.callAsSystem(() -> doVerify(inputEmbeddings));
    }

    private ApiResponse<Map<String, Object>> doVerify(List<List<Double>> inputEmbeddings) {
        // 查询所有已注册声纹（跨用户 1:N 比对）
        List<VoiceprintEmbedding> allRecords = embeddingMapper.selectList(
                new LambdaQueryWrapper<VoiceprintEmbedding>());

        if (allRecords.isEmpty()) {
            // 不回显相似度分数，防止阈值探测（SEC-007）
            return ApiResponse.ok(Map.of("matched", false));
        }

        // 按 userId 分组
        Map<UUID, List<VoiceprintEmbedding>> byUser = new LinkedHashMap<>();
        for (VoiceprintEmbedding rec : allRecords) {
            byUser.computeIfAbsent(rec.getUserId(), k -> new ArrayList<>()).add(rec);
        }

        log.info("[声纹验证] 输入 {} 段 embedding, 库中 {} 条记录, {} 个用户, 阈值={}",
                inputEmbeddings.size(), allRecords.size(), byUser.size(), verifyThreshold);

        double bestScore = 0;
        UUID bestUserId = null;
        UUID bestTenantId = null;

        for (Map.Entry<UUID, List<VoiceprintEmbedding>> entry : byUser.entrySet()) {
            for (List<Double> inputEmb : inputEmbeddings) {
                for (VoiceprintEmbedding stored : entry.getValue()) {
                    List<Double> storedEmb = parseEmbedding(stored.getEmbedding());
                    if (storedEmb == null) continue;
                    double score = cosineSimilarity(inputEmb, storedEmb);
                    if (score > bestScore) {
                        bestScore = score;
                        bestUserId = entry.getKey();
                        bestTenantId = entry.getValue().get(0).getTenantId();
                    }
                }
            }
        }

        log.info("[声纹验证] bestScore={}, bestUserId={}, matched={}",
                String.format("%.4f", bestScore), bestUserId, bestScore >= verifyThreshold);

        boolean matched = bestScore >= verifyThreshold;
        if (!matched) {
            // P0-3：失败审计——库非空时记录 VOICEPRINT_VERIFY_FAILED，暴力探测可追踪；
            // 库为空（bestUserId=null）无比对对象，静默返回
            if (bestUserId != null && bestTenantId != null) {
                auditLogService.log(bestTenantId, bestUserId, "VOICEPRINT_VERIFY_FAILED",
                        "user", bestUserId, null);
            }
            return ApiResponse.ok(Map.of("matched", false));
        }

        // 匹配成功：查用户 + 签发 token
        User user = userMapper.selectById(bestUserId);
        if (user == null || !"active".equals(user.getStatus())
                || !tenantAccessGuard.isLoginAllowed(user.getTenantId())) {
            return ApiResponse.ok(Map.of("matched", false));
        }

        // 更新最后登录时间
        User update = new User();
        update.setUserId(user.getUserId());
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);

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

    // ===== 内部工具 =====

    /**
     * 解析客户端 IP（P0-3 防伪造）：
     * - 经 nginx 代理：X-Forwarded-For 取最右条目——nginx 用 $proxy_add_x_forwarded_for
     *   在真实客户端 IP 前追加客户端提供的头，最右 = 不可伪造的真实 IP
     * - 直连：使用 remoteAddr
     * 直连伪造面已收窄：docker-compose 将 8080 绑定 127.0.0.1，公网唯一入口为 nginx。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int idx = forwarded.lastIndexOf(',');
            return (idx >= 0 ? forwarded.substring(idx + 1) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size()) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private String toJson(List<Double> embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "embedding 序列化失败");
        }
    }

    private List<Double> parseEmbedding(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Request DTOs =====

    public record EnrollRequest(
            @NotEmpty(message = "embeddings 不能为空")
            List<List<Double>> embeddings
    ) {}

    public record VerifyRequest(
            @NotEmpty(message = "embeddings 不能为空")
            List<List<Double>> embeddings
    ) {}
}
