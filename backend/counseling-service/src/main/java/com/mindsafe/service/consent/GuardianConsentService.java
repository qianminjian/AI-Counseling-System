package com.mindsafe.service.consent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ConsentRecord;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.sms.PhoneVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 监护人同意闭环服务（AUTH-023，PIPL §31 未成年人单独同意）
 * <p>
 * 流程：
 * <ol>
 *   <li>学生注册/首次使用 → 系统发起监护人同意请求（发送短信验证码到 guardianPhone）</li>
 *   <li>监护人收到验证码 → 告知学生 → 学生输入验证码确认</li>
 *   <li>验证通过 → 写入 consent_records（type=guardian_consent）→ 学生账号激活</li>
 * </ol>
 * 不满 14 周岁的未成年人处理个人信息需监护人单独同意（PIPL 第31条）。
 */
@Service
public class GuardianConsentService {

    private static final Logger log = LoggerFactory.getLogger(GuardianConsentService.class);

    /** 监护人同意版本号（与告知同意条款版本对齐） */
    private static final String CONSENT_VERSION = "v1.0";
    private static final String CONSENT_TYPE = "guardian_consent";

    private final PhoneVerificationService phoneVerificationService;
    private final ConsentRecordMapper consentRecordMapper;
    private final UserMapper userMapper;
    private final AuditLogService auditLogService;

    public GuardianConsentService(PhoneVerificationService phoneVerificationService,
                                  ConsentRecordMapper consentRecordMapper,
                                  UserMapper userMapper,
                                  AuditLogService auditLogService) {
        this.phoneVerificationService = phoneVerificationService;
        this.consentRecordMapper = consentRecordMapper;
        this.userMapper = userMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 发起监护人同意请求：发送验证码到监护人手机。
     *
     * @param tenantId      租户 ID
     * @param studentUserId 学生用户 ID
     * @param guardianPhone 监护人手机号
     */
    public void requestConsent(UUID tenantId, UUID studentUserId, String guardianPhone) {
        // 验证学生存在
        User student = userMapper.selectById(studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }

        // 检查是否已有有效的监护人同意记录
        Long existing = consentRecordMapper.selectCount(
                new LambdaQueryWrapper<ConsentRecord>()
                        .eq(ConsentRecord::getUserId, studentUserId)
                        .eq(ConsentRecord::getTenantId, tenantId)
                        .eq(ConsentRecord::getConsentType, CONSENT_TYPE)
        );
        if (existing > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该学生已有监护人同意记录，无需重复操作");
        }

        // 发送验证码
        phoneVerificationService.sendCode(guardianPhone, "监护人同意确认");
        log.info("监护人同意验证码已发送: studentUserId={}, phone={}", studentUserId,
                guardianPhone.substring(0, 3) + "****" + guardianPhone.substring(guardianPhone.length() - 4));
    }

    /**
     * 确认监护人同意：验证码校验 + 写入同意记录。
     *
     * @param tenantId      租户 ID
     * @param studentUserId 学生用户 ID
     * @param guardianPhone 监护人手机号
     * @param code          验证码
     */
    public void confirmConsent(UUID tenantId, UUID studentUserId, String guardianPhone, String code) {
        // 验证码校验
        boolean verified = phoneVerificationService.verifyCode(guardianPhone, code);
        if (!verified) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "验证码错误或已过期");
        }

        // 写入同意记录
        ConsentRecord record = ConsentRecord.create(studentUserId, tenantId, CONSENT_TYPE, CONSENT_VERSION);
        consentRecordMapper.insert(record);

        // 审计日志
        auditLogService.log(tenantId, studentUserId, "GUARDIAN_CONSENT", "user", studentUserId,
                "监护人同意确认（PIPL §31），版本 " + CONSENT_VERSION);

        log.info("监护人同意闭环完成: studentUserId={}", studentUserId);
    }

    /**
     * 检查学生是否已获得监护人同意。
     */
    public boolean hasGuardianConsent(UUID tenantId, UUID studentUserId) {
        Long count = consentRecordMapper.selectCount(
                new LambdaQueryWrapper<ConsentRecord>()
                        .eq(ConsentRecord::getUserId, studentUserId)
                        .eq(ConsentRecord::getTenantId, tenantId)
                        .eq(ConsentRecord::getConsentType, CONSENT_TYPE)
        );
        return count != null && count > 0;
    }
}
