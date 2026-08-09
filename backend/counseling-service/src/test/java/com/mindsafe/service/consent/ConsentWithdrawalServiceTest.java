package com.mindsafe.service.consent;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ConsentRecord;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 同意撤回服务单测（AUTH-032，PIPL §47）
 * 覆盖 BUG-P-P04-01 新增的 getConsentStatus（授权状态/时间/版本查询）
 */
@ExtendWith(MockitoExtension.class)
class ConsentWithdrawalServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID STUDENT_ID = UUID.randomUUID();

    @Mock
    private UserMapper userMapper;
    @Mock
    private StudentProfileMapper profileMapper;
    @Mock
    private ConsentRecordMapper consentRecordMapper;
    @Mock
    private AuditLogService auditLogService;

    private ConsentWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new ConsentWithdrawalService(userMapper, profileMapper, consentRecordMapper, auditLogService);
    }

    private User student(String status) {
        User u = new User();
        u.setUserId(STUDENT_ID);
        u.setTenantId(TENANT_ID);
        u.setPseudonym("小星星");
        u.setStatus(status);
        return u;
    }

    private ConsentRecord consentRecord(String type, String version) {
        ConsentRecord r = ConsentRecord.create(STUDENT_ID, TENANT_ID, type, version);
        return r;
    }

    // ===== getConsentStatus（BUG-P-P04-01） =====

    @Test
    void 已授权状态返回active与授权版本时间() {
        when(userMapper.selectById(STUDENT_ID)).thenReturn(student("active"));
        // 第一次调用=guardian_consent 查询；第二次调用=consent_withdrawal 查询（无撤回记录）
        when(consentRecordMapper.selectOne(any()))
                .thenReturn(consentRecord("guardian_consent", "v1.0"))
                .thenReturn(null);

        Map<String, Object> result = service.getConsentStatus(TENANT_ID, STUDENT_ID);

        assertEquals("active", result.get("status"));
        assertEquals("v1.0", result.get("consentVersion"));
        assertNotNull(result.get("consentedAt"));
        assertNull(result.get("withdrawnAt"));
        assertEquals("小星星", result.get("studentNickname"));
    }

    @Test
    void 已撤回状态返回withdrawn与撤回时间() {
        when(userMapper.selectById(STUDENT_ID)).thenReturn(student("withdrawn"));
        when(consentRecordMapper.selectOne(any()))
                .thenReturn(consentRecord("guardian_consent", "v1.0"))
                .thenReturn(consentRecord("consent_withdrawal", "v0.1"));

        Map<String, Object> result = service.getConsentStatus(TENANT_ID, STUDENT_ID);

        assertEquals("withdrawn", result.get("status"));
        assertNotNull(result.get("withdrawnAt"));
    }

    @Test
    void 学生不存在时查询应拒绝() {
        when(userMapper.selectById(STUDENT_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.getConsentStatus(TENANT_ID, STUDENT_ID));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND.code(), e.getCode());
    }

    // ===== withdrawConsent 幂等 =====

    @Test
    void 已撤回状态重复撤回应幂等返回() {
        when(userMapper.selectById(STUDENT_ID)).thenReturn(student("withdrawn"));

        service.withdrawConsent(TENANT_ID, STUDENT_ID);

        // 不抛异常即幂等通过；不再写库
        org.mockito.Mockito.verify(userMapper, org.mockito.Mockito.never())
                .updateById(any(User.class));
    }
}
