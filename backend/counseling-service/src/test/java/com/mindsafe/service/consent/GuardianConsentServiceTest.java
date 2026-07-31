package com.mindsafe.service.consent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ConsentRecord;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.sms.PhoneVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 监护人同意闭环单测（AUTH-040，PIPL §31）
 */
@ExtendWith(MockitoExtension.class)
class GuardianConsentServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID STUDENT_ID = UUID.randomUUID();
    private static final String PHONE = "13800138000";

    @Mock
    private PhoneVerificationService phoneVerificationService;
    @Mock
    private ConsentRecordMapper consentRecordMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AuditLogService auditLogService;

    private GuardianConsentService service;

    @BeforeEach
    void setUp() {
        service = new GuardianConsentService(
                phoneVerificationService, consentRecordMapper, userMapper, auditLogService);
    }

    // ===== requestConsent =====

    @Test
    void 学生不存在时发起请求应拒绝() {
        when(userMapper.selectById(STUDENT_ID)).thenReturn(null);

        assertThrows(BizException.class,
                () -> service.requestConsent(TENANT_ID, STUDENT_ID, PHONE));
        verify(phoneVerificationService, never()).sendCode(any(), any());
    }

    @Test
    void 已有同意记录时重复发起应拒绝() {
        when(userMapper.selectById(STUDENT_ID)).thenReturn(new User());
        when(consentRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(BizException.class,
                () -> service.requestConsent(TENANT_ID, STUDENT_ID, PHONE));
        verify(phoneVerificationService, never()).sendCode(any(), any());
    }

    @Test
    void 正常发起请求应发送验证码() {
        when(userMapper.selectById(STUDENT_ID)).thenReturn(new User());
        when(consentRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.requestConsent(TENANT_ID, STUDENT_ID, PHONE);

        verify(phoneVerificationService).sendCode(eq(PHONE), any());
    }

    // ===== confirmConsent =====

    @Test
    void 验证码错误时确认应拒绝且不写记录() {
        when(phoneVerificationService.verifyCode(PHONE, "000000")).thenReturn(false);

        assertThrows(BizException.class,
                () -> service.confirmConsent(TENANT_ID, STUDENT_ID, PHONE, "000000"));
        verify(consentRecordMapper, never()).insert(any(ConsentRecord.class));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 验证码正确时确认应写入同意记录并留审计() {
        when(phoneVerificationService.verifyCode(PHONE, "123456")).thenReturn(true);

        service.confirmConsent(TENANT_ID, STUDENT_ID, PHONE, "123456");

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordMapper).insert(captor.capture());
        ConsentRecord record = captor.getValue();
        assertEquals(STUDENT_ID, record.getUserId());
        assertEquals(TENANT_ID, record.getTenantId());
        assertEquals("guardian_consent", record.getConsentType());

        verify(auditLogService).log(eq(TENANT_ID), eq(STUDENT_ID), eq("GUARDIAN_CONSENT"),
                eq("user"), eq(STUDENT_ID), any());
    }

    // ===== hasGuardianConsent =====

    @Test
    void 存在同意记录时返回true() {
        when(consentRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertTrue(service.hasGuardianConsent(TENANT_ID, STUDENT_ID));
    }

    @Test
    void 无同意记录时返回false() {
        when(consentRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        assertFalse(service.hasGuardianConsent(TENANT_ID, STUDENT_ID));
    }
}
