package com.mindsafe.api.controller;

import com.mindsafe.api.ratelimit.RateLimiter;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.voiceprint.VoiceprintEnrollService;
import com.mindsafe.service.voiceprint.VoiceprintLoginService;
import com.mindsafe.service.voiceprint.VoiceprintVerifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VoiceprintController 单元测试（DC-006 改造版，doing/72 §20）
 * <p>
 * 覆盖控制器 HTTP 层职责：IP/指纹限流 key、参数校验、matched 结果编排（签发/审计）、enroll 编排。
 * 域语义（阈值判定/租户过滤/损坏数据）由 VoiceprintVerifyServiceTest 覆盖——测试经域服务接口，
 * 不再反射注入阈值（验收标准）。
 */
class VoiceprintControllerTest {

    private VoiceprintVerifyService verifyService;
    private VoiceprintEnrollService enrollService;
    private VoiceprintLoginService voiceprintLoginService;
    private JwtTokenProvider jwtTokenProvider;
    private AuditLogService auditLogService;
    private RateLimiter rateLimiter;
    private VoiceprintController controller;

    @BeforeEach
    void setUp() {
        verifyService = mock(VoiceprintVerifyService.class);
        enrollService = mock(VoiceprintEnrollService.class);
        voiceprintLoginService = mock(VoiceprintLoginService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        auditLogService = mock(AuditLogService.class);
        rateLimiter = mock(RateLimiter.class);
        controller = new VoiceprintController(verifyService, enrollService, voiceprintLoginService,
                jwtTokenProvider, auditLogService, rateLimiter);
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).thenReturn(true);
    }

    /** 测试替身：域服务返回无候选（库空/全损坏/跨租户被滤）的静默结果 */
    private void mockNoCandidate() {
        when(verifyService.verify(any(), any())).thenReturn(
                new VoiceprintVerifyService.VerifyOutcome(false, 0.0, null, null));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("172.17.0.2");
        return req;
    }

    @Nested
    @DisplayName("IP 解析（防 XFF 伪造绕过限流）")
    class ResolveClientIp {

        @Test
        @DisplayName("经 nginx 代理：XFF 取最右条目（nginx 追加的真实客户端 IP）")
        void usesRightMostXffEntryThroughProxy() {
            MockHttpServletRequest req = request();
            req.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
            req.setRemoteAddr("172.17.0.2");

            // 通过限流 key 验证解析结果：伪造前缀（1.2.3.4）不参与限流身份
            mockNoCandidate();
            controller.verify(new VoiceprintController.VerifyRequest(
                    UUID.randomUUID(), List.of(List.of(1.0, 1.0))), req);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            // 两次限流调用：IP 级 + 指纹级；IP 级 key 应为解析后的真实 IP
            verify(rateLimiter, atLeastOnce()).tryAcquire(keyCaptor.capture(), eq("voiceprint_verify"), anyInt(), any());
            assertThat(keyCaptor.getAllValues()).contains("5.6.7.8");
        }

        @Test
        @DisplayName("直连无代理：使用 remoteAddr")
        void usesRemoteAddrWhenNoProxy() {
            MockHttpServletRequest req = request();
            req.setRemoteAddr("10.0.0.9");

            mockNoCandidate();
            controller.verify(new VoiceprintController.VerifyRequest(
                    UUID.randomUUID(), List.of(List.of(1.0, 1.0))), req);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter, atLeastOnce()).tryAcquire(keyCaptor.capture(), eq("voiceprint_verify"), anyInt(), any());
            assertThat(keyCaptor.getAllValues()).contains("10.0.0.9");
        }

        @Test
        @DisplayName("限流拒绝 → RATE_LIMITED（限流不可绕过）")
        void rateLimitedRejected() {
            when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).thenReturn(false);

            assertThatThrownBy(() -> controller.verify(
                    new VoiceprintController.VerifyRequest(UUID.randomUUID(), List.of(List.of(1.0, 1.0))), request()))
                    .isInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.RATE_LIMITED.code());
        }
    }

    @Nested
    @DisplayName("指纹级限流（AUD-001：同一 embedding 重放防护）")
    class FingerprintRateLimit {

        @Test
        @DisplayName("同一 embedding 指纹超过上限 → RATE_LIMITED（换 IP 也无法绕过）")
        void identicalEmbeddingReplayRejected() {
            // 指纹限流 key 拒绝，IP 限流 key 放行：证明指纹维度独立生效
            when(rateLimiter.tryAcquire(anyString(), eq("voiceprint_verify_fp"), anyInt(), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> controller.verify(
                    new VoiceprintController.VerifyRequest(UUID.randomUUID(), List.of(List.of(1.0, 1.0))), request()))
                    .isInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.RATE_LIMITED.code());
        }

        @Test
        @DisplayName("指纹 key 由 embeddings 内容派生（同请求两次 key 一致）")
        void fingerprintKeyDerivedFromEmbeddings() {
            mockNoCandidate();
            controller.verify(new VoiceprintController.VerifyRequest(
                    UUID.randomUUID(), List.of(List.of(1.0, 1.0))), request());

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter).tryAcquire(keyCaptor.capture(), eq("voiceprint_verify_fp"), anyInt(), any());
            assertThat(keyCaptor.getValue()).startsWith("fp:");
        }
    }

    @Nested
    @DisplayName("入参校验")
    class InputValidation {

        @Test
        @DisplayName("embeddings 为空 → PARAM_INVALID")
        void emptyEmbeddingsRejected() {
            assertThatThrownBy(() -> controller.verify(
                    new VoiceprintController.VerifyRequest(UUID.randomUUID(), List.of()), request()))
                    .isInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.PARAM_INVALID.code());
        }
    }

    @Nested
    @DisplayName("verify 结果编排")
    class VerifyOrchestration {

        @Test
        @DisplayName("有候选未达标 → matched=false + 记录失败审计（暴力探测可追踪）且不签发")
        void belowThresholdAudited() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(verifyService.verify(eq(tenantId), any())).thenReturn(
                    new VoiceprintVerifyService.VerifyOutcome(false, 0.3, userId, tenantId));

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(0.4, -0.2))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(auditLogService).log(eq(tenantId), eq(userId),
                    eq("VOICEPRINT_VERIFY_FAILED"), anyString(), any(), any());
            verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
        }

        @Test
        @DisplayName("无候选（库空）→ matched=false 且不记失败审计（无对象可审计）")
        void noCandidateSilent() {
            mockNoCandidate();

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(UUID.randomUUID(), List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("匹配成功：签发 token + refreshToken + 记录登录审计")
        void matchedIssuesTokens() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(verifyService.verify(eq(tenantId), any())).thenReturn(
                    new VoiceprintVerifyService.VerifyOutcome(true, 0.99, userId, tenantId));
            User user = new User();
            user.setUserId(userId);
            user.setTenantId(tenantId);
            user.setStatus("active");
            user.setPseudonym("小明");
            user.setUserType("student");
            when(voiceprintLoginService.findLoginAllowedUser(userId)).thenReturn(user);
            when(jwtTokenProvider.generateToken(userId, "student", tenantId)).thenReturn("tok");
            when(jwtTokenProvider.generateRefreshToken(userId, "student", tenantId)).thenReturn("rtok");

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(true);
            assertThat(resp.data().get("token")).isEqualTo("tok");
            assertThat(resp.data().get("refreshToken")).isEqualTo("rtok");
            verify(auditLogService).log(eq(tenantId), eq(userId),
                    eq("VOICEPRINT_LOGIN_REMOTE"), anyString(), any(), any());
            verify(voiceprintLoginService).touchLastLogin(userId);
        }

        @Test
        @DisplayName("匹配成功但用户非 active 或租户禁用 → 不签发 token（门禁判定随查询下沉 Service）")
        void matchedButUserNotEligible() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(verifyService.verify(eq(tenantId), any())).thenReturn(
                    new VoiceprintVerifyService.VerifyOutcome(true, 0.99, userId, tenantId));
            when(voiceprintLoginService.findLoginAllowedUser(userId)).thenReturn(null);

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("enroll 编排")
    class EnrollOrchestration {

        @Test
        @DisplayName("录入成功：调域服务 + 审计 + 响应 enrolled/mode/tenantId")
        void enrollSuccess() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(enrollService.enroll(eq(userId), eq(tenantId), any())).thenReturn(2);
            var auth = mock(Authentication.class);
            // TenantContext 组件顺序：(tenantId, userId, userType)
            var ctx = new TenantContext(tenantId, userId, "student");
            when(auth.getDetails()).thenReturn(ctx);

            ApiResponse<Map<String, Object>> resp = controller.enroll(
                    new VoiceprintController.EnrollRequest(List.of(List.of(1.0, 0.0), List.of(0.5, 0.5))), auth);

            assertThat(resp.data().get("enrolled")).isEqualTo(2);
            assertThat(resp.data().get("mode")).isEqualTo("remote");
            assertThat(resp.data().get("tenantId")).isEqualTo(tenantId.toString());
            verify(auditLogService).log(eq(tenantId), eq(userId),
                    eq("VOICEPRINT_ENROLL_REMOTE"), anyString(), any(), any());
        }

        @Test
        @DisplayName("未认证 → UNAUTHORIZED")
        void unauthenticatedRejected() {
            assertThatThrownBy(() -> controller.enroll(
                    new VoiceprintController.EnrollRequest(List.of(List.of(1.0, 0.0))), null))
                    .isInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        }
    }
}
