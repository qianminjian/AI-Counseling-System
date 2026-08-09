package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.consent.ConsentWithdrawalService;
import com.mindsafe.service.parent.ParentService;
import com.mindsafe.service.sms.PhoneVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ParentController 单元测试（B1 租户上下文修复）
 * <p>
 * 覆盖：
 * - getWeeklyReport 从 token 绑定 TenantContextHolder，查询后清除
 * - withdrawConsent 从 token 绑定 TenantContextHolder，查询后清除
 * - student userType 被拒绝（学生自持 token 不可调家长接口）
 * - 无效 token 返回 UNAUTHORIZED
 * - verifyPhone 验证通过后签发正式 token
 */
class ParentControllerTest {

    private JwtTokenProvider jwtTokenProvider;
    private ParentService parentService;
    private ConsentWithdrawalService consentWithdrawalService;
    private PhoneVerificationService phoneVerificationService;
    private ParentController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private static final String VALID_PARENT_TOKEN = "parent-token-valid";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        parentService = mock(ParentService.class);
        consentWithdrawalService = mock(ConsentWithdrawalService.class);
        phoneVerificationService = mock(PhoneVerificationService.class);

        controller = new ParentController(jwtTokenProvider, parentService,
                consentWithdrawalService, phoneVerificationService);

        // 默认：有效 parent token
        when(jwtTokenProvider.validateToken(VALID_PARENT_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken(VALID_PARENT_TOKEN)).thenReturn(false);
        when(jwtTokenProvider.isVoiceCredential(VALID_PARENT_TOKEN)).thenReturn(false);
        when(jwtTokenProvider.getUserType(VALID_PARENT_TOKEN)).thenReturn("parent");
        when(jwtTokenProvider.getUserId(VALID_PARENT_TOKEN)).thenReturn(studentUserId);
        when(jwtTokenProvider.getTenantId(VALID_PARENT_TOKEN)).thenReturn(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private User studentUser() {
        User u = new User();
        u.setUserId(studentUserId);
        u.setTenantId(tenantId);
        u.setPseudonym("小星星");
        u.setGradeCode("GRADE_6");
        u.setClassCode("CLASS_1");
        return u;
    }

    // ===== 租户上下文绑定与清除 =====

    @Test
    @DisplayName("getWeeklyReport 从 token 绑定租户上下文，执行期间 TenantContextHolder 为有效值")
    void getWeeklyReportBindsTenantContext() {
        User student = studentUser();
        when(parentService.getRecentSessions(any(), any(), any())).thenReturn(List.of());
        when(parentService.getRecentStudentMessages(any(), any(), any())).thenReturn(List.of());

        when(parentService.getStudent(tenantId, studentUserId)).thenAnswer(inv -> {
            assertEquals(tenantId, TenantContextHolder.get(),
                    "getStudent 查询前 TenantContextHolder 应为 token 中的 tenantId");
            return student;
        });

        ApiResponse<Map<String, Object>> resp = controller.getWeeklyReport("Bearer " + VALID_PARENT_TOKEN);

        assertThat(resp.code()).isEqualTo(0);
        assertNull(TenantContextHolder.get(), "请求结束后 TenantContextHolder 必须为 null");
    }

    @Test
    @DisplayName("withdrawConsent 从 token 绑定租户上下文并调用服务")
    void withdrawConsentBindsTenantContext() {
        User student = studentUser();

        when(parentService.getStudent(tenantId, studentUserId)).thenAnswer(inv -> {
            assertEquals(tenantId, TenantContextHolder.get(),
                    "getStudent 查询前 TenantContextHolder 应为 token 中的 tenantId");
            return student;
        });

        ApiResponse<Map<String, Object>> resp = controller.withdrawConsent("Bearer " + VALID_PARENT_TOKEN);

        assertThat(resp.code()).isEqualTo(0);
        verify(consentWithdrawalService).withdrawConsent(tenantId, studentUserId);
        assertNull(TenantContextHolder.get(), "请求结束后 TenantContextHolder 必须为 null");
    }

    // ===== 安全门禁 =====

    @Nested
    @DisplayName("学生自持 token 拒绝")
    class StudentTokenRejected {

        @BeforeEach
        void setUp() {
            when(jwtTokenProvider.getUserType(VALID_PARENT_TOKEN)).thenReturn("student");
        }

        @Test
        @DisplayName("getWeeklyReport student token → UNAUTHORIZED")
        void reportRejected() {
            assertThatThrownBy(() -> controller.getWeeklyReport("Bearer " + VALID_PARENT_TOKEN))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        }

        @Test
        @DisplayName("withdrawConsent student token → UNAUTHORIZED")
        void withdrawRejected() {
            assertThatThrownBy(() -> controller.withdrawConsent("Bearer " + VALID_PARENT_TOKEN))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        }

        @Test
        @DisplayName("sendCode student token → UNAUTHORIZED")
        void sendCodeRejected() {
            assertThatThrownBy(() -> controller.sendVerificationCode("Bearer " + VALID_PARENT_TOKEN,
                    Map.of("phone", "13800000001")))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        }
    }

    @Test
    @DisplayName("无效 JWT → UNAUTHORIZED")
    void invalidTokenRejected() {
        String bad = "Bearer invalid.token.here";
        when(jwtTokenProvider.validateToken(anyString())).thenReturn(false);

        assertThatThrownBy(() -> controller.getWeeklyReport(bad))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    // ===== P1 审计修复：监护人同意撤回后旧 token 失效 =====

    @Nested
    @DisplayName("撤回同意（status=withdrawn）拒绝")
    class WithdrawnStudentRejected {

        private void mockWithdrawnStudent() {
            User student = studentUser();
            student.setStatus("withdrawn");
            when(parentService.getStudent(tenantId, studentUserId)).thenReturn(student);
        }

        @Test
        @DisplayName("getWeeklyReport 撤回后 → CONSENT_WITHDRAWN（不查询会话数据）")
        void reportRejected() {
            mockWithdrawnStudent();

            assertThatThrownBy(() -> controller.getWeeklyReport("Bearer " + VALID_PARENT_TOKEN))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CONSENT_WITHDRAWN.code());
            verify(parentService, org.mockito.Mockito.never()).getRecentSessions(any(), any(), any());
            assertNull(TenantContextHolder.get(), "拒绝路径也不得泄漏租户上下文");
        }

        @Test
        @DisplayName("withdrawConsent 撤回后重复请求 → CONSENT_WITHDRAWN")
        void withdrawRejected() {
            mockWithdrawnStudent();

            assertThatThrownBy(() -> controller.withdrawConsent("Bearer " + VALID_PARENT_TOKEN))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CONSENT_WITHDRAWN.code());
            verify(consentWithdrawalService, org.mockito.Mockito.never()).withdrawConsent(any(), any());
        }

        @Test
        @DisplayName("verifyPhone 撤回后 → CONSENT_WITHDRAWN（不签发新 token）")
        void verifyPhoneRejected() {
            mockWithdrawnStudent();

            assertThatThrownBy(() -> controller.verifyPhone(
                    "Bearer " + VALID_PARENT_TOKEN,
                    Map.of("phone", "13800000001", "code", "123456")))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.CONSENT_WITHDRAWN.code());
        }

        @Test
        @DisplayName("学生查无此人 → UNAUTHORIZED")
        void missingStudentRejected() {
            when(parentService.getStudent(tenantId, studentUserId)).thenReturn(null);

            assertThatThrownBy(() -> controller.getWeeklyReport("Bearer " + VALID_PARENT_TOKEN))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        }
    }

    // ===== BUG-P-P04-01：consent/status 宽松校验 =====

    @Nested
    @DisplayName("consent/status 授权状态查询")
    class ConsentStatus {

        @Test
        @DisplayName("正常返回授权状态并绑定租户上下文")
        void returnsStatus() {
            Map<String, Object> expected = new java.util.LinkedHashMap<>();
            expected.put("status", "active");
            expected.put("consentVersion", "v1.0");
            when(consentWithdrawalService.getConsentStatus(tenantId, studentUserId))
                    .thenAnswer(inv -> {
                        assertEquals(tenantId, TenantContextHolder.get(),
                                "getConsentStatus 查询前 TenantContextHolder 应为 token 中的 tenantId");
                        return expected;
                    });

            ApiResponse<Map<String, Object>> resp = controller.getConsentStatus("Bearer " + VALID_PARENT_TOKEN);

            assertThat(resp.code()).isEqualTo(0);
            assertThat(resp.data().get("status")).isEqualTo("active");
            assertNull(TenantContextHolder.get(), "请求结束后 TenantContextHolder 必须为 null");
        }

        @Test
        @DisplayName("撤回状态下仍可查询（宽松校验，不拦截 withdrawn）")
        void worksAfterWithdrawn() {
            // 不 mock parentService.getStudent —— 宽松路径不检查学生状态；
            // 只需证明没有触发严格校验的 UNAUTHORIZED/CONSENT_WITHDRAWN
            Map<String, Object> expected = Map.of("status", "withdrawn");
            when(consentWithdrawalService.getConsentStatus(tenantId, studentUserId)).thenReturn(expected);

            ApiResponse<Map<String, Object>> resp = controller.getConsentStatus("Bearer " + VALID_PARENT_TOKEN);

            assertThat(resp.code()).isEqualTo(0);
            assertThat(resp.data().get("status")).isEqualTo("withdrawn");
            verify(consentWithdrawalService).getConsentStatus(tenantId, studentUserId);
        }

        @Test
        @DisplayName("student token → UNAUTHORIZED")
        void studentTokenRejected() {
            when(jwtTokenProvider.getUserType(VALID_PARENT_TOKEN)).thenReturn("student");

            assertThatThrownBy(() -> controller.getConsentStatus("Bearer " + VALID_PARENT_TOKEN))
                    .isExactlyInstanceOf(BizException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        }
    }

    // ===== verifyPhone 签发正式 token =====

    @Test
    @DisplayName("verifyPhone 验证通过后签发正式 parent_report token")
    void verifyPhoneIssuesToken() {
        User student = studentUser();
        when(parentService.getStudent(tenantId, studentUserId)).thenReturn(student);
        when(phoneVerificationService.verifyCode("13800000001", "123456")).thenReturn(true);

        String formalToken = "formal-parent-token";
        when(jwtTokenProvider.generateParentReportToken(studentUserId, tenantId)).thenReturn(formalToken);

        ApiResponse<Map<String, Object>> resp = controller.verifyPhone(
                "Bearer " + VALID_PARENT_TOKEN,
                Map.of("phone", "13800000001", "code", "123456"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("token")).isEqualTo(formalToken);
        assertNull(TenantContextHolder.get());
    }

    @Test
    @DisplayName("verifyPhone 验证码错误 → UNAUTHORIZED")
    void verifyPhoneWrongCode() {
        User student = studentUser();
        when(parentService.getStudent(tenantId, studentUserId)).thenReturn(student);
        when(phoneVerificationService.verifyCode("13800000001", "wrong")).thenReturn(false);

        assertThatThrownBy(() -> controller.verifyPhone(
                "Bearer " + VALID_PARENT_TOKEN,
                Map.of("phone", "13800000001", "code", "wrong")))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }
}
