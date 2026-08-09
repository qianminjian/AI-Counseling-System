package com.mindsafe.service.auth;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ConsentRecord;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.TrialInviteCodeMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TrialAuthService 单元测试（审计 P1-17：auth 全链路补测）
 * 覆盖：试用注册（同意版本/昵称/邀请码/监护人同意）、PIN 登录（重名拒绝/租户门禁）、改密、设 PIN
 */
@ExtendWith(MockitoExtension.class)
class TrialAuthServiceTest {

    @Mock
    private TrialInviteCodeMapper inviteCodeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ConsentRecordMapper consentRecordMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PasswordPolicyService passwordPolicyService;
    @Mock
    private TenantAccessGuard tenantAccessGuard;

    private TrialAuthService service;

    @BeforeEach
    void setUp() {
        service = new TrialAuthService(inviteCodeMapper, userMapper, consentRecordMapper,
                passwordEncoder, passwordPolicyService, tenantAccessGuard);
        ReflectionTestUtils.setField(service, "trialAutoGrantGuardianConsent", true);
    }

    private TrialInviteCode usableCode() {
        TrialInviteCode code = new TrialInviteCode();
        code.setCodeId(UUID.randomUUID());
        code.setTenantId(TrialAuthService.TRIAL_TENANT_ID);
        code.setCode("TRIAL123");
        code.setStatus("active");
        code.setMaxUses(1);
        code.setUsedCount(0);
        return code;
    }

    // ===== registerTrialUser =====

    @Test
    @DisplayName("同意版本不匹配 → 拒绝注册")
    void consentVersionMismatchRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.registerTrialUser("TRIAL123", "小明同学", 10, null, "boy", "v0.0", null));
        assertEquals(ErrorCode.CONSENT_VERSION_MISMATCH.code(), ex.getCode());
    }

    @Test
    @DisplayName("昵称过短/过长/含敏感词 → 拒绝注册")
    void invalidNicknameRejected() {
        assertThrows(BizException.class,
                () -> service.registerTrialUser("TRIAL123", "明", 10, null, "boy", "v0.1", null));
        assertThrows(BizException.class,
                () -> service.registerTrialUser("TRIAL123", "这是一个超过十二个字符的很长昵称哦", 10, null, "boy", "v0.1", null));
        assertThrows(BizException.class,
                () -> service.registerTrialUser("TRIAL123", "管理员小号", 10, null, "boy", "v0.1", null));
    }

    @Test
    @DisplayName("邀请码不存在/不可用 → 拒绝注册")
    void invalidInviteCodeRejected() {
        when(inviteCodeMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class,
                () -> service.registerTrialUser("WRONG", "小明同学", 10, null, "boy", "v0.1", null));

        TrialInviteCode disabled = usableCode();
        disabled.setStatus("disabled");
        when(inviteCodeMapper.selectOne(any())).thenReturn(disabled);
        assertThrows(BizException.class,
                () -> service.registerTrialUser("TRIAL123", "小明同学", 10, null, "boy", "v0.1", null));
    }

    @Test
    @DisplayName("邀请码用尽 → 拒绝注册")
    void exhaustedInviteCodeRejected() {
        TrialInviteCode code = usableCode();
        code.setUsedCount(1);
        code.setMaxUses(1);
        when(inviteCodeMapper.selectOne(any())).thenReturn(code);
        assertThrows(BizException.class,
                () -> service.registerTrialUser("TRIAL123", "小明同学", 10, null, "boy", "v0.1", null));
    }

    @Test
    @DisplayName("注册成功：trial_student + 双同意留痕 + 单次码绑定 + PIN 原子写入")
    void registerHappyPath() {
        when(inviteCodeMapper.selectOne(any())).thenReturn(usableCode());
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("1234")).thenReturn("pin-hash");

        User user = service.registerTrialUser("TRIAL123", "小明同学", 10, "parent", "boy", "v0.1", "1234");

        assertEquals("trial_student", user.getUserType());
        assertEquals(TrialAuthService.TRIAL_TENANT_ID, user.getTenantId());
        assertEquals(TrialAuthService.TRIAL_SCHOOL_ID, user.getSchoolId());
        assertEquals("pin-hash", user.getPinHash());
        assertNotNull(user.getFamilyCode());

        // 双同意留痕：trial_terms + guardian_consent（age<14 且试运行开关开）
        ArgumentCaptor<ConsentRecord> consentCaptor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordMapper, times(2)).insert(consentCaptor.capture());
        List<String> types = consentCaptor.getAllValues().stream()
                .map(ConsentRecord::getConsentType).toList();
        assertTrue(types.contains("trial_terms"));
        assertTrue(types.contains("guardian_consent"));

        // 单次码（maxUses=1）绑定用户 + 计数 +1
        ArgumentCaptor<TrialInviteCode> codeCaptor = ArgumentCaptor.forClass(TrialInviteCode.class);
        verify(inviteCodeMapper).updateById(codeCaptor.capture());
        assertEquals(1, codeCaptor.getValue().getUsedCount());
        assertEquals(user.getUserId(), codeCaptor.getValue().getBoundUserId());

        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("fail-closed：未显式开启试运行开关时，age<14 不自动写入监护人同意（PIPL §31）")
    void guardianConsentNotAutoGrantedWhenSwitchOff() {
        // 默认不设置 trialAutoGrantGuardianConsent（=false，fail-closed）
        ReflectionTestUtils.setField(service, "trialAutoGrantGuardianConsent", false);
        when(inviteCodeMapper.selectOne(any())).thenReturn(usableCode());
        when(userMapper.selectCount(any())).thenReturn(0L);

        service.registerTrialUser("TRIAL123", "小明同学", 10, null, "boy", "v0.1", null);

        // 仅写 trial_terms，不写 guardian_consent（须由真实监护人 SMS 闭环产生）
        ArgumentCaptor<ConsentRecord> consentCaptor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordMapper, times(1)).insert(consentCaptor.capture());
        assertEquals("trial_terms", consentCaptor.getValue().getConsentType());
    }

    @Test
    @DisplayName("age>=14：本人同意即生效，无需试运行开关（与监护人同意逻辑正交）")
    void teenSelfConsentWorksRegardlessOfSwitch() {
        ReflectionTestUtils.setField(service, "trialAutoGrantGuardianConsent", false);
        when(inviteCodeMapper.selectOne(any())).thenReturn(usableCode());
        when(userMapper.selectCount(any())).thenReturn(0L);

        service.registerTrialUser("TRIAL123", "小明同学", 15, null, "boy", "v0.1", null);

        // age>=14：本人勾选即生效 → trial_terms + guardian_consent 双留痕
        ArgumentCaptor<ConsentRecord> consentCaptor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordMapper, times(2)).insert(consentCaptor.capture());
        List<String> types = consentCaptor.getAllValues().stream()
                .map(ConsentRecord::getConsentType).toList();
        assertTrue(types.contains("guardian_consent"));
    }

    // ===== loginWithPin =====

    @Test
    @DisplayName("PIN 登录：昵称重名 → 拒绝（SEC-003 防随机命中）")
    void duplicateNicknameRejected() {
        User u1 = new User();
        User u2 = new User();
        when(userMapper.selectList(any())).thenReturn(List.of(u1, u2));
        assertThrows(BizException.class, () -> service.loginWithPin("小明同学", "1234"));
    }

    @Test
    @DisplayName("PIN 登录：用户不存在/无 PIN/PIN 错误 → 统一拒绝且不泄露原因")
    void wrongPinRejected() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        assertThrows(BizException.class, () -> service.loginWithPin("不存在", "1234"));

        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setPinHash("stored-hash");
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(passwordEncoder.matches("9999", "stored-hash")).thenReturn(false);
        assertThrows(BizException.class, () -> service.loginWithPin("小明同学", "9999"));
    }

    @Test
    @DisplayName("PIN 登录：租户 suspended → FORBIDDEN（SEC-004）")
    void suspendedTenantRejected() {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setTenantId(UUID.randomUUID());
        user.setPinHash("stored-hash");
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(passwordEncoder.matches("1234", "stored-hash")).thenReturn(true);
        when(tenantAccessGuard.isLoginAllowed(user.getTenantId())).thenReturn(false);

        assertThrows(BizException.class, () -> service.loginWithPin("小明同学", "1234"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("F-1: PIN 登录冻结账号（withdrawn）→ FORBIDDEN 专属提示（不泄露 PIN 校验）")
    void withdrawnAccountFrozen() {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setTenantId(UUID.randomUUID());
        user.setStatus(User.STATUS_WITHDRAWN);
        user.setPinHash("stored-hash");
        when(userMapper.selectList(any())).thenReturn(List.of(user));

        BizException ex = assertThrows(BizException.class, () -> service.loginWithPin("小明同学", "1234"));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.getCode());
        assertTrue(ex.getMessage().contains("冻结"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("PIN 登录成功 → 返回用户并更新最后登录时间")
    void loginSuccess() {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setTenantId(UUID.randomUUID());
        user.setPinHash("stored-hash");
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(passwordEncoder.matches("1234", "stored-hash")).thenReturn(true);
        when(tenantAccessGuard.isLoginAllowed(user.getTenantId())).thenReturn(true);

        User result = service.loginWithPin("小明同学", "1234");

        assertEquals(user.getUserId(), result.getUserId());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertNotNull(captor.getValue().getLastLoginAt());
    }

    // ===== changePassword =====

    @Test
    @DisplayName("改密：用户不存在/旧密码错误 → 拒绝")
    void changePasswordRejectsBadOldPassword() {
        UUID userId = UUID.randomUUID();
        when(userMapper.selectById(userId)).thenReturn(null);
        assertThrows(BizException.class, () -> service.changePassword(userId, "old", "NewPass123"));

        User user = new User();
        user.setUserId(userId);
        user.setPasswordHash("hash");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        assertThrows(BizException.class, () -> service.changePassword(userId, "wrong", "NewPass123"));
    }

    @Test
    @DisplayName("改密成功：走复杂度校验 + 清除强制改密标记")
    void changePasswordSuccess() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setUserId(userId);
        user.setPasswordHash("hash");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(passwordEncoder.matches("OldPass1", "hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hash");

        service.changePassword(userId, "OldPass1", "NewPass123");

        verify(passwordPolicyService).validateComplexity("NewPass123");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("new-hash", captor.getValue().getPasswordHash());
        assertFalse(captor.getValue().getMustChangePassword());
    }

    // ===== setPin =====

    @Test
    @DisplayName("设置 PIN：非 4-6 位数字 → 拒绝")
    void invalidPinFormatRejected() {
        assertThrows(BizException.class, () -> service.setPin(UUID.randomUUID(), "12"));
        assertThrows(BizException.class, () -> service.setPin(UUID.randomUUID(), "abcd"));
        assertThrows(BizException.class, () -> service.setPin(UUID.randomUUID(), null));
    }

    @Test
    @DisplayName("设置 PIN 成功：写入 pinHash + pinSetAt")
    void setPinSuccess() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setUserId(userId);
        when(userMapper.selectById(userId)).thenReturn(user);
        when(passwordEncoder.encode("4321")).thenReturn("pin-hash");

        service.setPin(userId, "4321");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("pin-hash", captor.getValue().getPinHash());
        assertNotNull(captor.getValue().getPinSetAt());
    }
}
