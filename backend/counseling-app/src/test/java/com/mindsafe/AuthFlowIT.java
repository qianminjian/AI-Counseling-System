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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证流程集成测试（TrialInviteCode → Register → Login → JWT 鉴权）
 * <p>
 * 验证完整链路：
 * 1. 邀请码验证（DEMO2026 种子数据）
 * 2. 试用注册（自动创建租户）
 * 3. 登录获取 JWT
 * 4. JWT 鉴权访问受保护端点
 * 5. 未认证访问被拒绝
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static String studentToken;
    private static String studentUserId;

    @Test
    @Order(1)
    void validateInviteCode_demo2026_shouldReturnActive() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/validate-code",
                Map.of("inviteCode", "DEMO2026"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("valid").asBoolean()).isTrue();
        assertThat(body.get("data").get("schoolName").asText()).isEqualTo("演示学校");
    }

    @Test
    @Order(2)
    void validateInviteCode_invalid_shouldReturnInvalid() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/validate-code",
                Map.of("inviteCode", "INVALID999"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("data").get("valid").asBoolean()).isFalse();
    }

    @Test
    @Order(3)
    void trialRegister_shouldCreateTenantAndStudent() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/trial-register",
                Map.of(
                        "inviteCode", "DEMO2026",
                        "schoolName", "集成测试学校",
                        "grade", 4,
                        "classNo", 2,
                        "studentNo", "IT001",
                        "nickname", "测试小明",
                        "avatarId", "cat"
                ),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("token").asText()).isNotBlank();
        assertThat(body.get("data").get("isNewTenant").asBoolean()).isTrue();

        studentToken = body.get("data").get("token").asText();
        studentUserId = body.get("data").get("userId").asText();
    }

    @Test
    @Order(4)
    void login_withCreatedStudent_shouldReturnToken() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("userId", studentUserId, "password", "a1b2c3"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("userType").asText()).isEqualTo("student");

        // 更新 token 为登录返回的
        studentToken = body.get("data").get("token").asText();
    }

    @Test
    @Order(5)
    void authenticatedRequest_shouldSucceed() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/sessions?limit=5",
                HttpMethod.GET, entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
    }

    @Test
    @Order(6)
    void unauthenticatedRequest_shouldReturn401() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/sessions", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(7)
    void studentAccessAdminEndpoint_shouldReturn403() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(studentToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/invite-codes",
                HttpMethod.GET, entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
