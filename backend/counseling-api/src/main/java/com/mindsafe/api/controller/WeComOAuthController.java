package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.wecom.WeComOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企业微信 OAuth2 集成（教师免密登录）
 * <p>
 * 流程：前端跳转企微授权页 → 用户确认 → 回调带 code → 后端换取 userId → 签发 JWT
 * <p>
 * 配置项（application.yml）：
 *   wecom.corp-id, wecom.agent-id, wecom.secret, wecom.redirect-uri
 */
@RestController
@RequestMapping("/api/v1/auth/wecom")
public class WeComOAuthController {

    private static final Logger log = LoggerFactory.getLogger(WeComOAuthController.class);

    @Value("${wecom.corp-id:}")
    private String corpId;

    @Value("${wecom.agent-id:}")
    private String agentId;

    @Value("${wecom.secret:}")
    private String secret;

    @Value("${wecom.redirect-uri:}")
    private String redirectUri;

    private final JwtTokenProvider jwtTokenProvider;
    private final WeComOAuthService weComOAuthService;
    private final RestTemplate restTemplate = buildRestTemplate();

    /** 企微外呼必须带超时：避免登录链路被外部服务挂死 */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }

    public WeComOAuthController(JwtTokenProvider jwtTokenProvider, WeComOAuthService weComOAuthService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.weComOAuthService = weComOAuthService;
    }

    /** 获取企微 OAuth 授权 URL（前端跳转用） */
    @GetMapping("/auth-url")
    public ApiResponse<Map<String, Object>> getAuthUrl() {
        if (corpId.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "企业微信未配置");
        }
        String url = String.format(
                "https://open.weixin.qq.com/connect/oauth2/authorize?appid=%s&redirect_uri=%s&response_type=code&scope=snsapi_privateinfo&agentid=%s#wechat_redirect",
                corpId, redirectUri, agentId);
        return ApiResponse.ok(Map.of("authUrl", url, "enabled", true));
    }

    /**
     * OAuth 回调：用 code 换取企微 userId，再匹配系统用户签发 JWT
     * <p>
     * 简化实现：实际生产需调用企微 API 获取 access_token → user_info
     */
    @PostMapping("/callback")
    public ApiResponse<Map<String, Object>> callback(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少授权码");
        }

        if (corpId.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "企业微信未配置");
        }

        try {
            // Step 1: 获取 access_token
            String tokenUrl = String.format(
                    "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s", corpId, secret);
            Map tokenResp = restTemplate.getForObject(tokenUrl, Map.class);
            String accessToken = (String) tokenResp.get("access_token");

            // Step 2: 用 code 换取用户身份
            String userInfoUrl = String.format(
                    "https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo?access_token=%s&code=%s", accessToken, code);
            Map userResp = restTemplate.getForObject(userInfoUrl, Map.class);
            String wecomUserId = (String) userResp.get("userid");

            if (wecomUserId == null) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "企微授权失败，未获取到用户身份");
            }

            // Step 3: 匹配系统用户（用 pseudonym 字段匹配企微 userId，待 wecom_user_id 字段上线后切换）
            // 前置认证链路（无 JWT，跨租户匹配教师）：系统作用域在 Service 内声明（M1-003 fail-fast 配套）
            log.info("企微 OAuth 登录: wecomUserId={}", wecomUserId);
            User matchedUser = weComOAuthService.findTeacherByWeComId(wecomUserId);

            if (matchedUser == null) {
                return ApiResponse.ok(Map.of(
                        "matched", false,
                        "wecomUserId", wecomUserId,
                        "message", "企微用户已识别，请在管理后台绑定系统账号"
                ));
            }

            // Step 4: 更新最后登录时间（已识别出租户，绑定真实租户上下文执行）+ 签发 JWT
            // T4 批次B：登录时间更新下沉 WeComOAuthService（租户上下文绑定在 Service 内）
            weComOAuthService.touchLastLogin(matchedUser.getTenantId(), matchedUser.getUserId());

            String token = jwtTokenProvider.generateToken(
                    matchedUser.getUserId(), matchedUser.getUserType(), matchedUser.getTenantId());
            String refreshToken = jwtTokenProvider.generateRefreshToken(
                    matchedUser.getUserId(), matchedUser.getUserType(), matchedUser.getTenantId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matched", true);
            result.put("token", token);
            result.put("refreshToken", refreshToken);
            result.put("userId", matchedUser.getUserId().toString());
            result.put("userType", matchedUser.getUserType());
            result.put("tenantId", matchedUser.getTenantId().toString());
            log.info("企微 OAuth 登录成功: userId={}, tenant={}", matchedUser.getUserId(), matchedUser.getTenantId());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("企微 OAuth 回调处理失败", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "企微登录处理失败: " + e.getMessage());
        }
    }
}
