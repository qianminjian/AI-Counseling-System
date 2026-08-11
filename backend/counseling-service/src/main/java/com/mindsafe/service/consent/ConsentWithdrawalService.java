package com.mindsafe.service.consent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ConsentRecord;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同意撤回服务（AUTH-032，PIPL §47 删除权）
 * <p>
 * 家长（监护人）可随时撤回对未成年人数据处理的同意。撤回后：
 * <ol>
 *   <li>冻结学生账号（status=withdrawn，登录接口因 status≠active 而拒绝）</li>
 *   <li>删除学生心理画像（结构化统计数据）</li>
 *   <li>写入撤回同意留痕 + 审计日志</li>
 * </ol>
 * 幂等：重复撤回直接返回。原始对话消息的保留期清理由 AUTH-031 数据保留策略负责。
 */
@Service
public class ConsentWithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(ConsentWithdrawalService.class);

    /** 撤回后账号状态（F-1：常量收敛至 User.STATUS_WITHDRAWN，此处保留兼容引用） */
    public static final String STATUS_WITHDRAWN = User.STATUS_WITHDRAWN;

    private final UserMapper userMapper;
    private final StudentProfileMapper profileMapper;
    private final ConsentRecordMapper consentRecordMapper;
    private final AuditLogService auditLogService;

    public ConsentWithdrawalService(UserMapper userMapper,
                                    StudentProfileMapper profileMapper,
                                    ConsentRecordMapper consentRecordMapper,
                                    AuditLogService auditLogService) {
        this.userMapper = userMapper;
        this.profileMapper = profileMapper;
        this.consentRecordMapper = consentRecordMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 撤回同意：冻结账号 + 删除画像 + 留痕。
     *
     * @param tenantId      租户 ID
     * @param studentUserId 学生用户 ID
     */
    @Transactional
    public void withdrawConsent(UUID tenantId, UUID studentUserId) {
        User student = userMapper.selectById(studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }
        // 幂等：已撤回则不再处理
        if (STATUS_WITHDRAWN.equals(student.getStatus())) {
            log.info("账号已处于撤回状态，忽略重复请求: studentUserId={}", studentUserId);
            return;
        }

        // 1. 冻结账号
        User update = new User();
        update.setUserId(studentUserId);
        update.setStatus(STATUS_WITHDRAWN);
        update.setUpdatedAt(Instant.now());
        userMapper.updateById(update);

        // 2. 删除心理画像（PIPL §47）
        int deletedProfiles = profileMapper.delete(
                new LambdaQueryWrapper<StudentProfile>()
                        .eq(StudentProfile::getTenantId, tenantId)
                        .eq(StudentProfile::getUserId, studentUserId)
        );

        // 3. 撤回同意留痕
        ConsentRecord record = ConsentRecord.create(
                studentUserId, tenantId, "consent_withdrawal", "v0.1");
        consentRecordMapper.insert(record);

        // doing/92 R-007：删除旧的 guardian_consent 记录（撤回后允许重新申请；
        // 审计链由 consent_withdrawal 留痕承担，guardian_consent 删除不破坏审计）
        consentRecordMapper.delete(
                new LambdaQueryWrapper<ConsentRecord>()
                        .eq(ConsentRecord::getUserId, studentUserId)
                        .eq(ConsentRecord::getTenantId, tenantId)
                        .eq(ConsentRecord::getConsentType, GuardianConsentService.CONSENT_TYPE));

        // 4. 审计日志（doing/92 R-009：撤回删除范围清单——声纹/记忆/日记/对话随保留期
        // 由 DataRetentionCleanupJob 处理（AUTH-031），撤回学生优先清理登记为后续项）
        auditLogService.log(tenantId, studentUserId, "CONSENT_WITHDRAW", "user", studentUserId,
                "家长撤回同意：冻结账号 + 删除画像 " + deletedProfiles + " 条"
                        + "（声纹/长期记忆/日记/对话按保留期清理，撤回学生优先清理待 DataRetentionCleanupJob 支持）");

        log.info("同意撤回完成: studentUserId={}, deletedProfiles={}", studentUserId, deletedProfiles);
    }

    /**
     * 查询监护人对某学生的授权状态（BUG-P-P04-01：P-04 同意管理页展示用）。
     * <p>
     * 注意：本方法不要求学生 status=active —— 撤回后家长仍需能查看"已撤回"状态。
     *
     * @return 状态摘要：status(active/withdrawn) + 授权版本/时间 + 撤回时间（若有）
     */
    public Map<String, Object> getConsentStatus(UUID tenantId, UUID studentUserId) {
        User student = userMapper.selectById(studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }
        boolean withdrawn = STATUS_WITHDRAWN.equals(student.getStatus());

        // 最近一条监护人同意留痕（guardian_consent）
        ConsentRecord consent = consentRecordMapper.selectOne(
                new LambdaQueryWrapper<ConsentRecord>()
                        .eq(ConsentRecord::getTenantId, tenantId)
                        .eq(ConsentRecord::getUserId, studentUserId)
                        .eq(ConsentRecord::getConsentType, "guardian_consent")
                        .orderByDesc(ConsentRecord::getConsentedAt)
                        .last("LIMIT 1")
        );
        // 最近一条撤回留痕（consent_withdrawal）
        ConsentRecord withdrawal = consentRecordMapper.selectOne(
                new LambdaQueryWrapper<ConsentRecord>()
                        .eq(ConsentRecord::getTenantId, tenantId)
                        .eq(ConsentRecord::getUserId, studentUserId)
                        .eq(ConsentRecord::getConsentType, "consent_withdrawal")
                        .orderByDesc(ConsentRecord::getConsentedAt)
                        .last("LIMIT 1")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", withdrawn ? STATUS_WITHDRAWN : "active");
        result.put("consentVersion", consent != null ? consent.getConsentVersion() : null);
        result.put("consentedAt", consent != null ? consent.getConsentedAt() : null);
        result.put("withdrawnAt", withdrawal != null ? withdrawal.getConsentedAt() : null);
        result.put("studentNickname", student.getPseudonym());
        return result;
    }
}
