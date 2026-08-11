package com.mindsafe.api.security;

import com.mindsafe.domain.entity.TocFamilyAccount;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * toC 认证 Provider（doing/89 N-001 步骤 4，AC-89-06）
 * <p>
 * token 签发从 Controller 下沉至独立组件（修复分层倒挂——"service 不依赖 api"
 * 的正确落位：签发逻辑在 api 层但独立于 Controller，为未来统一 AuthProvider 接缝预留）。
 * 本组件是 toC 体系（手机号验证码）的 token 组装唯一出口。
 */
@Component
public class TocAuthProvider {

    private final JwtTokenProvider jwtTokenProvider;

    public TocAuthProvider(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** 组装登录响应：token 签发（userType=toc_parent，tenantId=null 平台级）+ 账号信息。 */
    public Map<String, Object> buildSession(TocFamilyAccount account) {
        String token = jwtTokenProvider.generateToken(
                account.getFamilyAccountId(), "toc_parent", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("familyAccountId", account.getFamilyAccountId());
        result.put("phone", maskPhone(account.getPhone()));
        result.put("displayName", "家庭 " + maskPhone(account.getPhone()));
        return result;
    }

    private static String maskPhone(String phone) {
        return phone == null || phone.length() < 7 ? phone
                : phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
