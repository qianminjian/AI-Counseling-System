package com.mindsafe.api.auth;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.TrialAuthService;
import org.springframework.stereotype.Component;

/**
 * 试用认证策略（D1=成人体验者 / D2=邀请码 / D3=邀请码+昵称+年龄）
 * <p>
 * 流程：邀请码校验 → 昵称校验 → 创建 trial_student → 同意留痕 → 返回统一身份
 */
@Component
public class TrialAuthStrategy {

    private final TrialAuthService trialAuthService;

    public TrialAuthStrategy(TrialAuthService trialAuthService) {
        this.trialAuthService = trialAuthService;
    }


    /**
     * 试用注册认证（F19，doing/97：参数类型化——原 Object+instanceof，改直收 TrialRegisterRequest）
     */
    public AuthenticatedUser authenticate(TrialRegisterRequest req) {
        // age<14 需监护人手机号（PIPL 第31条；D1=成人体验者，试用阶段不强制但保留校验）
        if (req.age() < 14 && (req.guardianPhone() == null || req.guardianPhone().isBlank())) {
            throw new BizException(ErrorCode.CONSENT_REQUIRED,
                    "不满 14 周岁需提供监护人手机号");
        }

        User user = trialAuthService.registerTrialUser(
                req.inviteCode(), req.pseudonym(), req.age(),
                req.role(), req.gender(), req.consentVersion(), req.pin());

        return new AuthenticatedUser(
                user.getUserId(),
                user.getUserType(),
                user.getTenantId(),
                user.getPseudonym(),
                false  // trial_student 无密码，无需改密
        );
    }
}
