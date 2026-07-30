package com.mindsafe.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ConsentRecord;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ConsentRecordMapper;
import com.mindsafe.domain.mapper.TrialInviteCodeMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * 试用准入业务服务（邀请码校验 + 用户创建 + 同意留痕）
 * <p>
 * 试用租户固定 UUID：90000000-0000-0000-0000-000000000001
 * 当前生效告知同意版本：v0.1（对应 design/22 草稿）
 */
@Service
public class TrialAuthService {

    /** 试用租户固定 ID（V6 种子） */
    public static final UUID TRIAL_TENANT_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000001");
    /** 试用学校固定 ID（V6 种子） */
    public static final UUID TRIAL_SCHOOL_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000011");
    /** 当前生效的告知同意版本（与 design/22 一致） */
    public static final String CURRENT_CONSENT_VERSION = "v0.1";

    private final TrialInviteCodeMapper inviteCodeMapper;
    private final UserMapper userMapper;
    private final ConsentRecordMapper consentRecordMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;

    public TrialAuthService(TrialInviteCodeMapper inviteCodeMapper,
                            UserMapper userMapper,
                            ConsentRecordMapper consentRecordMapper,
                            PasswordEncoder passwordEncoder,
                            PasswordPolicyService passwordPolicyService) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.userMapper = userMapper;
        this.consentRecordMapper = consentRecordMapper;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicyService = passwordPolicyService;
    }

    /**
     * 试用注册：校验邀请码 → 创建 trial_student → 同意留痕 → PIN 原子写入 → 返回用户
     *
     * @param inviteCode     邀请码
     * @param pseudonym      昵称（2-12 字）
     * @param age            年龄
     * @param role           体验者身份（家长/老师/其他，可选）
     * @param consentVersion 同意的告知同意版本号
     * @param pin            PIN 码（4-6 位数字，可为 null 表示暂不设置）
     * @return 创建的试用用户
     */
    @Transactional
    public User registerTrialUser(String inviteCode, String pseudonym, int age,
                                  String role, String gender, String consentVersion, String pin) {
        // 1. 校验告知同意版本
        if (!CURRENT_CONSENT_VERSION.equals(consentVersion)) {
            throw new BizException(ErrorCode.CONSENT_VERSION_MISMATCH);
        }

        // 2. 校验昵称
        validateNickname(pseudonym);

        // 3. 校验邀请码
        TrialInviteCode code = validateAndConsumeInviteCode(inviteCode);

        // 4. 创建 trial_student 用户（试用用户无密码，凭 JWT 会话访问）
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setTenantId(TRIAL_TENANT_ID);
        user.setSchoolId(TRIAL_SCHOOL_ID);
        user.setUserType("trial_student");
        user.setPseudonym(pseudonym);
        user.setStatus("active");
        user.setMustChangePassword(false);
        user.setGender(gender);
        user.setFamilyCode(generateFamilyCode());
        // PIN 原子写入：注册时一并设置，避免二次 API 中断导致半成品用户
        if (pin != null && pin.matches("\\d{4,6}")) {
            user.setPinHash(passwordEncoder.encode(pin));
            user.setPinSetAt(Instant.now());
        }
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userMapper.insert(user);

        // 5. 同意留痕
        ConsentRecord consent = ConsentRecord.create(
                user.getUserId(), TRIAL_TENANT_ID, "trial_terms", consentVersion);
        consentRecordMapper.insert(consent);

        // 5.1 试运行阶段：age<14 自动写入监护人同意记录（无 SMS 网关，注册时已收集监护人手机号）
        //     正式上线后由 GuardianConsentService SMS 闭环替代，见 TASK-TRACKER AUTH-040
        if (age < 14) {
            ConsentRecord guardianConsent = ConsentRecord.create(
                    user.getUserId(), TRIAL_TENANT_ID, "guardian_consent", "v1.0");
            consentRecordMapper.insert(guardianConsent);
        }

        // 6. 邀请码使用计数 +1；单次码（max_uses=1）绑定用户，共享码不绑定
        TrialInviteCode update = new TrialInviteCode();
        update.setCodeId(code.getCodeId());
        update.setUsedCount(code.getUsedCount() + 1);
        if (code.getMaxUses() != null && code.getMaxUses() == 1) {
            update.setBoundUserId(user.getUserId());
        }
        update.setUsedAt(Instant.now());
        inviteCodeMapper.updateById(update);

        return user;
    }

    /**
     * 修改密码（首次设密 / 常规改密）
     *
     * @param userId      用户 ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     */
    @Transactional
    public void changePassword(UUID userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "旧密码错误");
        }
        // AUTH-014：密码复杂度校验（≥8位 + 字母+数字）
        passwordPolicyService.validateComplexity(newPassword);

        User update = new User();
        update.setUserId(userId);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        update.setMustChangePassword(false);
        update.setPasswordChangedAt(Instant.now());
        update.setUpdatedAt(Instant.now());
        userMapper.updateById(update);
    }

    /**
     * 设置 PIN 码（注册后学生设置 4-6 位数字 PIN）
     */
    @Transactional
    public void setPin(UUID userId, String pin) {
        if (pin == null || !pin.matches("\\d{4,6}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "PIN 码必须为 4-6 位数字");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        User update = new User();
        update.setUserId(userId);
        update.setPinHash(passwordEncoder.encode(pin));
        update.setPinSetAt(Instant.now());
        update.setUpdatedAt(Instant.now());
        userMapper.updateById(update);
    }

    /**
     * PIN 码登录（学生快捷登录）
     *
     * @param pseudonym 昵称
     * @param pin       4-6 位数字 PIN
     * @return 登录成功的用户
     */
    public User loginWithPin(String pseudonym, String pin) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPseudonym, pseudonym)
                        .eq(User::getStatus, "active")
                        .last("LIMIT 1")
        );
        if (user == null || user.getPinHash() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "昵称或 PIN 码错误");
        }
        if (!passwordEncoder.matches(pin, user.getPinHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "昵称或 PIN 码错误");
        }
        // 更新最后登录时间
        User update = new User();
        update.setUserId(user.getUserId());
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);
        return user;
    }

    // ===== 内部方法 =====

    private void validateNickname(String pseudonym) {
        if (pseudonym == null || pseudonym.length() < 2 || pseudonym.length() > 12) {
            throw new BizException(ErrorCode.NICKNAME_INVALID);
        }
        // 简单敏感词过滤（MVP 级别，正式期可接入 SafetyKeywordLibrary）
        String lower = pseudonym.toLowerCase();
        String[] blocked = {"admin", "root", "system", "管理员", "系统"};
        for (String word : blocked) {
            if (lower.contains(word)) {
                throw new BizException(ErrorCode.NICKNAME_INVALID, "昵称含敏感词");
            }
        }
    }

    /**
     * 生成 6 位家庭码（大写字母+数字，去除易混淆字符 0/O/1/I/L）
     */
    private String generateFamilyCode() {
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        // 最多尝试 10 次避免碰撞
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String code = sb.toString();
            // 检查唯一性
            Long count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getTenantId, TRIAL_TENANT_ID)
                            .eq(User::getFamilyCode, code)
            );
            if (count == 0) {
                return code;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "家庭码生成失败，请重试");
    }

    private TrialInviteCode validateAndConsumeInviteCode(String inviteCode) {
        TrialInviteCode code = inviteCodeMapper.selectOne(
                new LambdaQueryWrapper<TrialInviteCode>()
                        .eq(TrialInviteCode::getTenantId, TRIAL_TENANT_ID)
                        .eq(TrialInviteCode::getCode, inviteCode)
                        .last("LIMIT 1")
        );
        if (code == null || !code.isUsable()) {
            throw new BizException(ErrorCode.INVITE_CODE_INVALID);
        }
        if (code.getUsedCount() >= code.getMaxUses()) {
            throw new BizException(ErrorCode.INVITE_CODE_EXHAUSTED);
        }
        return code;
    }
}
