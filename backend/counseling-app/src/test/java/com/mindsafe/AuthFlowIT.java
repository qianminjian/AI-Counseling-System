package com.mindsafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证流程集成测试（试用注册 → JWT 鉴权 → 角色授权）
 * <p>
 * 对齐当前 API（design/22 试用注册 + Spring Security JWT）：
 * 1. 试用注册（DEMO2026 邀请码 + 昵称 + 年龄 + 监护人手机号 + 同意版本 v0.1）→ 返回 JWT
 * 2. 无效邀请码 → 业务失败（HTTP 200 + success=false）
 * 3. 不满 14 周岁缺监护人手机号 → CONSENT_REQUIRED（HTTP 200 + success=false）
 * 4. 持 token 访问 /auth/me → 成功
 * 5. 未认证访问 /sessions → 4xx（无自定义 EntryPoint，默认 403）
 * 6. 学生访问 /admin/** → 403
 * <p>
 * 错误模型约定：业务异常 BizException → HTTP 200（错误在 body.success/code）；
 * 安全拦截（未认证/无权限）→ HTTP 状态码（403）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIT extends AbstractIntegrationTest {

    /** 当前生效的告知同意版本（与 TrialAuthService.CURRENT_CONSENT_VERSION 一致） */
    private static final String CONSENT_VERSION = "v0.1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static String studentToken;

    /** 构造试用注册请求体（age=9，guardianPhone 为 null 时不传该字段） */
    private Map<String, Object> registerBody(String inviteCode, String pseudonym, String guardianPhone) {
        Map<String, Object> body = new HashMap<>();
        body.put("inviteCode", inviteCode);
        body.put("pseudonym", pseudonym);
        body.put("age", 9);
        body.put("consentVersion", CONSENT_VERSION);
        if (guardianPhone != null) {
            body.put("guardianPhone", guardianPhone);
        }
        return body;
    }

    @Test
    @Order(1)
    void trialRegister_valid_shouldReturnToken() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/trial/register",
                registerBody("DEMO2026", "集成小明", "13800138000"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("token").asText()).isNotBlank();
        assertThat(body.get("data").get("userType").asText()).isEqualTo("trial_student");

        studentToken = body.get("data").get("token").asText();
    }

    @Test
    @Order(2)
    void trialRegister_invalidInviteCode_shouldFail() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/trial/register",
                registerBody("INVALID999", "集成小红", "13800138001"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isFalse();
    }

    @Test
    @Order(3)
    void trialRegister_under14WithoutGuardianPhone_shouldFail() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/trial/register",
                registerBody("DEMO2026", "集成小刚", null),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isFalse();
    }

    @Test
    @Order(4)
    void authenticatedMe_shouldSucceed() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/me", HttpMethod.GET, entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("userType").asText()).isEqualTo("trial_student");
    }

    @Test
    @Order(5)
    void unauthenticatedSessions_shouldBeRejected() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/sessions", String.class);

        // 无自定义 AuthenticationEntryPoint，Spring Security 对未认证请求默认返回 403
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @Order(6)
    void studentAccessAdminEndpoint_shouldBeForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/invite-codes", HttpMethod.GET, entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
