package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

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
    private final RestTemplate restTemplate = new RestTemplate();

    public WeComOAuthController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** 获取企微 OAuth 授权 URL（前端跳转用） */
    @GetMapping("/auth-url")
    public ApiResponse<Map<String, Object>> getAuthUrl() {
        if (corpId.isBlank()) {
            return ApiResponse.error(501, "企业微信未配置");
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
            return ApiResponse.error(400, "缺少授权码");
        }

        if (corpId.isBlank()) {
            return ApiResponse.error(501, "企业微信未配置");
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
                return ApiResponse.error(401, "企微授权失败，未获取到用户身份");
            }

            // Step 3: 匹配系统用户（用 pseudonym 或扩展字段匹配）
            // 简化：直接用 wecomUserId 作为 pseudonym 查找
            // 生产环境应查 users 表的 wecom_user_id 字段
            log.info("企微 OAuth 登录: wecomUserId={}", wecomUserId);

            // TODO: 查库匹配用户，此处返回占位响应
            return ApiResponse.ok(Map.of(
                    "matched", false,
                    "wecomUserId", wecomUserId,
                    "message", "企微用户已识别，请在管理后台绑定系统账号"
            ));
        } catch (Exception e) {
            log.error("企微 OAuth 回调处理失败", e);
            return ApiResponse.error(500, "企微登录处理失败: " + e.getMessage());
        }
    }
}
