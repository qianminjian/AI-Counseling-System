package com.mindsafe.service.auth;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ParentAccount;
import com.mindsafe.domain.entity.ParentStudentLink;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ParentAccountMapper;
import com.mindsafe.domain.mapper.ParentStudentLinkMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ParentAuthService 单元测试（审计 P1-17：auth 全链路补测）
 * 覆盖：注册校验（手机号/密码/家庭码）、重复绑定拦截、租户由学生动态推导、登录、绑定学生查询
 */
@ExtendWith(MockitoExtension.class)
class ParentAuthServiceTest {

    @Mock
    private ParentAccountMapper parentAccountMapper;
    @Mock
    private ParentStudentLinkMapper parentStudentLinkMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private LoginLockoutService lockoutService;
    @Mock
    private TenantAccessGuard tenantAccessGuard;

    private ParentAuthService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // S-001：家长登录门禁默认放行（门禁拒绝场景单独用例覆盖；部分测试不走门禁需 lenient）
        lenient().when(tenantAccessGuard.isLoginAllowed(any())).thenReturn(true);
        service = new ParentAuthService(parentAccountMapper, parentStudentLinkMapper,
                userMapper, passwordEncoder, lockoutService, tenantAccessGuard);
    }

    private User student() {
        User student = new User();
        student.setUserId(studentUserId);
        student.setTenantId(tenantId);
        student.setFamilyCode("ABC123");
        student.setStatus("active");
        return student;
    }

    // ===== register =====

    @Test
    @DisplayName("手机号格式非法 → 拒绝注册")
    void invalidPhoneRejected() {
        assertThrows(BizException.class, () -> service.register("ABC123", "12345", "pass123", "father"));
        assertThrows(BizException.class, () -> service.register("ABC123", null, "pass123", "father"));
    }

    @Test
    @DisplayName("密码不足 6 位 → 拒绝注册")
    void weakPasswordRejected() {
        assertThrows(BizException.class, () -> service.register("ABC123", "13800138000", "12345", "father"));
    }

    @Test
    @DisplayName("家庭码无效 → 拒绝注册")
    void invalidFamilyCodeRejected() {
        when(userMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class,
                () -> service.register("NOPE", "13800138000", "pass123", "father"));
    }

    @Test
    @DisplayName("注册成功：租户由学生推导 + 账号与关联落库 + 关系映射显示名")
    void registerHappyPath() {
        when(userMapper.selectOne(any())).thenReturn(student());
        when(parentAccountMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("pass123")).thenReturn("pw-hash");

        ParentAccount account = service.register("ABC123", "13800138000", "pass123", "father");

        assertEquals(tenantId, account.getTenantId());
        assertEquals("爸爸", account.getDisplayName());
        assertEquals("pw-hash", account.getPasswordHash());

        verify(parentAccountMapper).insert(any(ParentAccount.class));
        ArgumentCaptor<ParentStudentLink> linkCaptor = ArgumentCaptor.forClass(ParentStudentLink.class);
        verify(parentStudentLinkMapper).insert(linkCaptor.capture());
        assertEquals(studentUserId, linkCaptor.getValue().getStudentUserId());
        assertEquals(account.getParentId(), linkCaptor.getValue().getParentId());
    }

    @Test
    @DisplayName("已注册且已绑定同一学生 → 拒绝重复绑定")
    void duplicateBindingRejected() {
        ParentAccount existing = new ParentAccount();
        existing.setParentId(UUID.randomUUID());
        when(userMapper.selectOne(any())).thenReturn(student());
        when(parentAccountMapper.selectOne(any())).thenReturn(existing);
        ParentStudentLink existLink = new ParentStudentLink();
        when(parentStudentLinkMapper.selectOne(any())).thenReturn(existLink);

        assertThrows(BizException.class,
                () -> service.register("ABC123", "13800138000", "pass123", "father"));
        verify(parentAccountMapper, never()).insert(any(ParentAccount.class));
    }

    @Test
    @DisplayName("已注册未绑定该学生 → 追加绑定不新建账号")
    void existingAccountAppendsLink() {
        ParentAccount existing = new ParentAccount();
        existing.setParentId(UUID.randomUUID());
        when(userMapper.selectOne(any())).thenReturn(student());
        when(parentAccountMapper.selectOne(any())).thenReturn(existing);
        when(parentStudentLinkMapper.selectOne(any())).thenReturn(null);

        ParentAccount result = service.register("ABC123", "13800138000", "pass123", "mother");

        assertEquals(existing.getParentId(), result.getParentId());
        verify(parentAccountMapper, never()).insert(any(ParentAccount.class));
        verify(parentStudentLinkMapper).insert(any(ParentStudentLink.class));
    }

    // ===== login =====

    @Test
    @DisplayName("登录：账号不存在/密码错误 → 统一拒绝")
    void loginRejectsBadCredentials() {
        when(parentAccountMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.login("13800138000", "whatever"));

        ParentAccount account = new ParentAccount();
        account.setParentId(UUID.randomUUID());
        account.setPasswordHash("pw-hash");
        when(parentAccountMapper.selectOne(any())).thenReturn(account);
        when(passwordEncoder.matches("wrong", "pw-hash")).thenReturn(false);
        assertThrows(BizException.class, () -> service.login("13800138000", "wrong"));
    }

    @Test
    @DisplayName("S-001：租户 suspended/archived → 家长登录 FORBIDDEN")
    void loginTenantSuspendedRejected() {
        ParentAccount account = new ParentAccount();
        account.setParentId(UUID.randomUUID());
        account.setTenantId(tenantId);
        account.setPasswordHash("pw-hash");
        when(parentAccountMapper.selectOne(any())).thenReturn(account);
        when(passwordEncoder.matches("pass123", "pw-hash")).thenReturn(true);
        when(tenantAccessGuard.isLoginAllowed(tenantId)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> service.login("13800138000", "pass123"));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.code());
    }

    @Test
    @DisplayName("登录成功 → 返回账号并更新最后登录时间")
    void loginSuccess() {
        ParentAccount account = new ParentAccount();
        account.setParentId(UUID.randomUUID());
        account.setPasswordHash("pw-hash");
        when(parentAccountMapper.selectOne(any())).thenReturn(account);
        when(passwordEncoder.matches("pass123", "pw-hash")).thenReturn(true);

        ParentAccount result = service.login("13800138000", "pass123");

        assertEquals(account.getParentId(), result.getParentId());
        ArgumentCaptor<ParentAccount> captor = ArgumentCaptor.forClass(ParentAccount.class);
        verify(parentAccountMapper).updateById(captor.capture());
        assertNotNull(captor.getValue().getLastLoginAt());
    }

    // ===== getLinkedStudents =====

    @Test
    @DisplayName("查询绑定学生：撤回冻结（withdrawn）学生保留可见，已删除/不存在过滤（BUG-P-P06-01）")
    void linkedStudentsFilterInactive() {
        UUID parentId = UUID.randomUUID();
        ParentStudentLink link1 = new ParentStudentLink();
        link1.setStudentUserId(UUID.randomUUID());
        ParentStudentLink link2 = new ParentStudentLink();
        link2.setStudentUserId(UUID.randomUUID());
        when(parentStudentLinkMapper.selectList(any())).thenReturn(List.of(link1, link2));

        User withdrawn = new User();
        withdrawn.setUserId(link1.getStudentUserId());
        withdrawn.setStatus(User.STATUS_WITHDRAWN);
        when(userMapper.selectById(link1.getStudentUserId())).thenReturn(withdrawn);
        when(userMapper.selectById(link2.getStudentUserId())).thenReturn(null);

        List<User> result = service.getLinkedStudents(parentId);

        assertEquals(1, result.size());
        assertEquals(link1.getStudentUserId(), result.get(0).getUserId());
        assertEquals(User.STATUS_WITHDRAWN, result.get(0).getStatus());
    }
}
