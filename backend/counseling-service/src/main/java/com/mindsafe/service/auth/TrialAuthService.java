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

    public TrialAuthService(TrialInviteCodeMapper inviteCodeMapper,
                            UserMapper userMapper,
                            ConsentRecordMapper consentRecordMapper,
                            PasswordEncoder passwordEncoder) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.userMapper = userMapper;
        this.consentRecordMapper = consentRecordMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 试用注册：校验邀请码 → 创建 trial_student → 同意留痕 → 返回用户
     *
     * @param inviteCode     邀请码
     * @param pseudonym      昵称（2-12 字）
     * @param age            年龄
     * @param role           体验者身份（家长/老师/其他，可选）
     * @param consentVersion 同意的告知同意版本号
     * @return 创建的试用用户
     */
    @Transactional
    public User registerTrialUser(String inviteCode, String pseudonym, int age,
                                  String role, String gender, String consentVersion) {
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
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userMapper.insert(user);

        // 5. 同意留痕
        ConsentRecord consent = ConsentRecord.create(
                user.getUserId(), TRIAL_TENANT_ID, "trial_terms", consentVersion);
        consentRecordMapper.insert(consent);

        // 6. 邀请码使用计数 +1（乐观更新）
        TrialInviteCode update = new TrialInviteCode();
        update.setCodeId(code.getCodeId());
        update.setUsedCount(code.getUsedCount() + 1);
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
        if (newPassword == null || newPassword.length() < 8) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码至少 8 位");
        }

        User update = new User();
        update.setUserId(userId);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        update.setMustChangePassword(false);
        update.setUpdatedAt(Instant.now());
        userMapper.updateById(update);
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
