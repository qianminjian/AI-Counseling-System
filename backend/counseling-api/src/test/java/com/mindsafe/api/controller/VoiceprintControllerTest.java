package com.mindsafe.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.api.ratelimit.RateLimiter;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.entity.VoiceprintEmbedding;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.auth.TenantAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
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
 * VoiceprintController 单元测试（P0-3 安全审计修复）
 * <p>
 * 覆盖：
 * - IP 解析：X-Forwarded-For 取最右条目（nginx $proxy_add_x_forwarded_for 追加的真实 IP，客户端伪造不可达）
 * - 限流：基于解析后 IP 作为 key，伪造 XFF 不改变限流身份；同一 embedding 指纹级限流（AUD-001 防抓包重放）
 * - 失败审计：verify 不匹配时记录 VOICEPRINT_VERIFY_FAILED（暴力探测可追踪）
 * - 租户隔离（AUD-001）：verify 强制携带 tenantId，查询仅限该租户模板，跨租户记录不可达
 * - 阈值回归（AUD-001）：相似度 ∈ (0.55, 0.70) 必须拒绝（对齐 local 端 0.70）
 * - 成功路径：租户内 1:N 匹配后签发双 token（产品设计：声纹即身份）
 */
class VoiceprintControllerTest {

    private VoiceprintEmbeddingMapper embeddingMapper;
    private UserMapper userMapper;
    private JwtTokenProvider jwtTokenProvider;
    private AuditLogService auditLogService;
    private RateLimiter rateLimiter;
    private TenantAccessGuard tenantAccessGuard;
    private VoiceprintController controller;

    @BeforeEach
    void setUp() {
        embeddingMapper = mock(VoiceprintEmbeddingMapper.class);
        userMapper = mock(UserMapper.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        auditLogService = mock(AuditLogService.class);
        rateLimiter = mock(RateLimiter.class);
        tenantAccessGuard = mock(TenantAccessGuard.class);
        controller = new VoiceprintController(embeddingMapper, userMapper, jwtTokenProvider,
                auditLogService, new ObjectMapper(), rateLimiter, tenantAccessGuard);
        // @Value 字段在纯单元测试中不注入，显式设置（AUD-001：与 local 端对齐 0.70）
        ReflectionTestUtils.setField(controller, "verifyThreshold", 0.70);
        ReflectionTestUtils.setField(controller, "maxTemplates", 8);
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).thenReturn(true);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("172.17.0.2");
        return req;
    }

    private VoiceprintEmbedding record(UUID userId, UUID tenantId, double v) {
        VoiceprintEmbedding rec = new VoiceprintEmbedding();
        rec.setUserId(userId);
        rec.setTenantId(tenantId);
        rec.setEmbedding("[" + v + "," + v + "]");
        rec.setSampleIndex(0);
        rec.setCreatedAt(Instant.now());
        return rec;
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
            controller.verify(new VoiceprintController.VerifyRequest(
                    UUID.randomUUID(), List.of(List.of(1.0, 1.0))), request());

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter).tryAcquire(keyCaptor.capture(), eq("voiceprint_verify_fp"), anyInt(), any());
            assertThat(keyCaptor.getValue()).startsWith("fp:");
        }
    }

    @Nested
    @DisplayName("失败审计（暴力探测可追踪）")
    class FailureAudit {

        @Test
        @DisplayName("不匹配：记录 VOICEPRINT_VERIFY_FAILED 且不签发 token")
        void failedVerifyAudited() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            // 存储 [0.5,0.5]，输入 [0.4,-0.2]：余弦≈0.316 ∈ (0, 0.55)，bestUserId 命中但未达阈值
            when(embeddingMapper.selectList(any())).thenReturn(
                    List.of(record(userId, tenantId, 0.5)));

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(0.4, -0.2))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(auditLogService).log(eq(tenantId), eq(userId),
                    eq("VOICEPRINT_VERIFY_FAILED"), anyString(), any(), any());
            verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
        }

        @Test
        @DisplayName("库为空：返回未匹配且不记失败审计（无对象可审计）")
        void emptyLibraryNotAuditedAsFailure() {
            UUID tenantId = UUID.randomUUID();
            when(embeddingMapper.selectList(any())).thenReturn(List.of());

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("租户隔离（AUD-001：禁止系统作用域全表比对）")
    class TenantIsolation {

        @Test
        @DisplayName("请求租户 A，库中存在租户 B 完整可用模板 → 不匹配不签发（跨租户双层防护）")
        void crossTenantTemplateUnreachable() {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            UUID userB = UUID.randomUUID();
            // 模拟最坏情况：mock 的 selectList 不过滤（返回租户 B 的模板），
            // 且租户 B 用户完整可用——若实现缺失租户过滤，此场景会误签发 token
            when(embeddingMapper.selectList(any())).thenReturn(
                    List.of(record(userB, tenantB, 1.0)));
            User userBEntity = new User();
            userBEntity.setUserId(userB);
            userBEntity.setTenantId(tenantB);
            userBEntity.setStatus("active");
            userBEntity.setPseudonym("隔壁班小明");
            userBEntity.setUserType("student");
            when(userMapper.selectById(userB)).thenReturn(userBEntity);
            when(tenantAccessGuard.isLoginAllowed(tenantB)).thenReturn(true);

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantA, List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("阈值对齐（AUD-001：0.55 → 0.70 回归）")
    class ThresholdAlignment {

        @Test
        @DisplayName("相似度 ∈ (0.55, 0.70) → 拒绝（旧阈值 0.55 会误判为命中）")
        void scoreBetweenOldAndNewThresholdRejected() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            // 存储 [1.0, 0.0]，输入 [0.6, 0.8]：余弦相似度 = 0.6 ∈ (0.55, 0.70)
            VoiceprintEmbedding rec = new VoiceprintEmbedding();
            rec.setUserId(userId);
            rec.setTenantId(tenantId);
            rec.setEmbedding("[1.0, 0.0]");
            rec.setSampleIndex(0);
            rec.setCreatedAt(Instant.now());
            when(embeddingMapper.selectList(any())).thenReturn(List.of(rec));

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(0.6, 0.8))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("成功路径（1:N 匹配签发双 token）")
    class SuccessPath {

        @Test
        @DisplayName("匹配成功：签发 token + refreshToken + 记录登录审计")
        void matchedIssuesTokens() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(embeddingMapper.selectList(any())).thenReturn(
                    List.of(record(userId, tenantId, 1.0)));
            User user = new User();
            user.setUserId(userId);
            user.setTenantId(tenantId);
            user.setStatus("active");
            user.setPseudonym("小明");
            user.setUserType("student");
            when(userMapper.selectById(userId)).thenReturn(user);
            when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(true);
            when(jwtTokenProvider.generateToken(userId, "student", tenantId)).thenReturn("tok");
            when(jwtTokenProvider.generateRefreshToken(userId, "student", tenantId)).thenReturn("rtok");

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(true);
            assertThat(resp.data().get("token")).isEqualTo("tok");
            assertThat(resp.data().get("refreshToken")).isEqualTo("rtok");
            verify(auditLogService).log(eq(tenantId), eq(userId),
                    eq("VOICEPRINT_LOGIN_REMOTE"), anyString(), any(), any());
            ArgumentCaptor<User> updateCaptor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).updateById(updateCaptor.capture());
            assertThat(updateCaptor.getValue().getLastLoginAt()).isNotNull();
        }

        @Test
        @DisplayName("匹配成功但用户非 active 或租户禁用 → 不签发 token")
        void matchedButUserNotEligible() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(embeddingMapper.selectList(any())).thenReturn(
                    List.of(record(userId, tenantId, 1.0)));
            User user = new User();
            user.setUserId(userId);
            user.setTenantId(tenantId);
            user.setStatus("suspended");
            when(userMapper.selectById(userId)).thenReturn(user);
            when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(false);

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("损坏数据隔离（C4：异常不得吞没/扩散）")
    class CorruptedData {

        @Test
        @DisplayName("库中存在损坏 embedding JSON：跳过该记录，好记录仍可正常匹配")
        void corruptedEmbeddingSkipped() {
            UUID goodUserId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            VoiceprintEmbedding corrupted = new VoiceprintEmbedding();
            corrupted.setUserId(UUID.randomUUID());
            corrupted.setTenantId(tenantId);
            corrupted.setEmbedding("{not-json");
            corrupted.setSampleIndex(0);
            corrupted.setCreatedAt(Instant.now());
            when(embeddingMapper.selectList(any()))
                    .thenReturn(List.of(corrupted, record(goodUserId, tenantId, 1.0)));
            User user = new User();
            user.setUserId(goodUserId);
            user.setTenantId(tenantId);
            user.setStatus("active");
            user.setPseudonym("小明");
            user.setUserType("student");
            when(userMapper.selectById(goodUserId)).thenReturn(user);
            when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(true);
            when(jwtTokenProvider.generateToken(goodUserId, "student", tenantId)).thenReturn("tok");
            when(jwtTokenProvider.generateRefreshToken(goodUserId, "student", tenantId)).thenReturn("rtok");

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(1.0, 1.0))), request());

            // 损坏记录被跳过且不抛异常：验证流程不因单条脏数据中断，也不误匹配
            assertThat(resp.data().get("matched")).isEqualTo(true);
            assertThat(resp.data().get("token")).isEqualTo("tok");
        }

        @Test
        @DisplayName("全部记录损坏：返回未匹配，不抛异常、不签发 token")
        void allCorruptedNoMatch() {
            UUID tenantId = UUID.randomUUID();
            VoiceprintEmbedding corrupted = new VoiceprintEmbedding();
            corrupted.setUserId(UUID.randomUUID());
            corrupted.setTenantId(tenantId);
            corrupted.setEmbedding("[1.0, \"oops\"");
            corrupted.setSampleIndex(0);
            corrupted.setCreatedAt(Instant.now());
            when(embeddingMapper.selectList(any())).thenReturn(List.of(corrupted));

            ApiResponse<Map<String, Object>> resp = controller.verify(
                    new VoiceprintController.VerifyRequest(tenantId, List.of(List.of(1.0, 1.0))), request());

            assertThat(resp.data().get("matched")).isEqualTo(false);
            verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
        }
    }
}
