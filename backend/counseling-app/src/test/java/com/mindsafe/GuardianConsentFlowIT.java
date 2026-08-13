package com.mindsafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 监护人同意 SMS 真实闭环集成测试（AUTH-040，PIPL §31）
 * <p>
 * 关闭试运行自动写入开关（mindsafe.consent.trial-auto-grant=false），走生产链路：
 * 1. age<14 注册 → guardianConsentPending=true（不自动写入同意记录）
 * 2. 未确认前创建会话 → CONSENT_REQUIRED(20003) 门禁拦截
 * 3. 发起监护人同意请求 → LoggingSmsService 发码，验证码落 Redis（sms:code:{phone}）
 * 4. 错误验证码确认 → 拒绝，门禁仍拦截
 * 5. 从 Redis 取真实验证码确认 → 写入 guardian_consent
 * 6. 再次创建会话 → 放行
 * 7. age>=14 注册 → 本人同意即生效（注册即写记录），guardianConsentPending=false，直接可对话
 * <p>
 * 错误模型约定（AUD-015）：业务异常 BizException → 按 ErrorCode 映射 4xx/5xx（错误在 body.code）：
 * 20001 未认证 → 401、20003 监护人授权缺失 → 403。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "mindsafe.consent.trial-auto-grant=false")
class GuardianConsentFlowIT extends AbstractIntegrationTest {

    private static final String CONSENT_VERSION = "v0.1";
    /** 监护人手机号（避开其他 IT 用的 13800138000，防 60s 发码冷却冲突） */
    private static final String GUARDIAN_PHONE = "13900139000";
    /** PhoneVerificationService 的 Redis 验证码 key 格式 */
    private static final String SMS_CODE_KEY = "sms:code:" + GUARDIAN_PHONE;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static String childToken;

    private Map<String, Object> registerBody(String pseudonym, int age, String guardianPhone) {
        Map<String, Object> body = new HashMap<>();
        body.put("inviteCode", "DEMO2026");
        body.put("pseudonym", pseudonym);
        body.put("age", age);
        body.put("consentVersion", CONSENT_VERSION);
        if (guardianPhone != null) {
            body.put("guardianPhone", guardianPhone);
        }
        return body;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** 创建会话（门禁靶点），返回完整响应（调用方按场景断言状态码与 body） */
    private ResponseEntity<String> createSession(String token) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("emotionTag", "happy", "channel", "web"), bearer(token));
        return restTemplate.postForEntity(
                "/api/v1/chat/sessions", entity, String.class);
    }

    @Test
    @Order(1)
    void 关闭自动写入时儿童注册应返回同意待办() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/trial/register",
                registerBody("闭环小玲", 9, GUARDIAN_PHONE),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(0);
        assertThat(body.get("data").get("guardianConsentPending").asBoolean()).isTrue();

        childToken = body.get("data").get("token").asText();
    }

    @Test
    @Order(2)
    void 未确认同意前创建会话应被门禁拦截() throws Exception {
        ResponseEntity<String> resp = createSession(childToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(20003);
    }

    @Test
    @Order(3)
    void 发起同意请求应发送验证码到Redis() throws Exception {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("guardianPhone", GUARDIAN_PHONE), bearer(childToken));
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/guardian-consent/request", entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(0);

        // LoggingSmsService 发码后验证码应落 Redis
        String code = stringRedisTemplate.opsForValue().get(SMS_CODE_KEY);
        assertThat(code).isNotNull().matches("\\d{6}");
    }

    @Test
    @Order(4)
    void 错误验证码确认应被拒绝() throws Exception {
        String realCode = stringRedisTemplate.opsForValue().get(SMS_CODE_KEY);
        String wrongCode = realCode.equals("000000") ? "111111" : "000000";

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("guardianPhone", GUARDIAN_PHONE, "code", wrongCode), bearer(childToken));
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/guardian-consent/confirm", entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(20001);

        // 门禁应仍然拦截（403）
        assertThat(createSession(childToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @Order(5)
    void 正确验证码确认后门禁应放行() throws Exception {
        String code = stringRedisTemplate.opsForValue().get(SMS_CODE_KEY);
        assertThat(code).isNotNull();

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("guardianPhone", GUARDIAN_PHONE, "code", code), bearer(childToken));
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/guardian-consent/confirm", entity, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(0);

        // 门禁放行，会话创建成功
        ResponseEntity<String> sessionResp = createSession(childToken);
        assertThat(sessionResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode session = objectMapper.readTree(sessionResp.getBody());
        assertThat(session.get("code").asInt()).isEqualTo(0);
        assertThat(session.get("data").get("sessionId").asText()).isNotBlank();
    }

    @Test
    @Order(6)
    void 十四岁以上注册本人同意即生效可直接对话() throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/trial/register",
                registerBody("闭环成年", 16, null),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(0);
        assertThat(body.get("data").get("guardianConsentPending").asBoolean()).isFalse();

        String token = body.get("data").get("token").asText();
        ResponseEntity<String> sessionResp = createSession(token);
        assertThat(sessionResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(sessionResp.getBody()).get("code").asInt()).isEqualTo(0);
    }
}
