package com.mindsafe.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmExtraBodyConfig 拦截器测试（doing/63：供应商专属参数通用化注入）
 * <p>
 * 覆盖 LLM-GEN-005/006/007：deepseek 自动注入、非 deepseek 不注入、显式 EXTRA_BODY 注入、非法 JSON 降级。
 */
class LlmExtraBodyConfigTest {

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String BAILIAN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String GLM_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpRequest request;
    private HttpHeaders headers;
    private ClientHttpRequestExecution execution;
    private ClientHttpResponse response;

    @BeforeEach
    void setUp() {
        request = mock(HttpRequest.class);
        headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        execution = mock(ClientHttpRequestExecution.class);
        response = mock(ClientHttpResponse.class);
        try {
            when(execution.execute(any(HttpRequest.class), any(byte[].class))).thenReturn(response);
        } catch (IOException e) {
            throw new RuntimeException("stub execute failed", e);
        }
    }

    private ClientHttpRequestInterceptor newInterceptor(String primaryBaseUrl, String backupBaseUrl,
                                                        String primaryExtraBody, String backupExtraBody) {
        return new LlmExtraBodyConfig.ExtraBodyInterceptor(
                objectMapper, primaryBaseUrl, backupBaseUrl, primaryExtraBody, backupExtraBody);
    }

    /** 执行拦截并返回实际传给 execution.execute 的请求体 */
    private String intercept(ClientHttpRequestInterceptor interceptor, String url, String bodyJson) throws IOException {
        when(request.getURI()).thenReturn(URI.create(url));
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        interceptor.intercept(request, bodyJson.getBytes(StandardCharsets.UTF_8), execution);
        verify(execution).execute(any(HttpRequest.class), bodyCaptor.capture());
        return new String(bodyCaptor.getValue(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("主 base-url 含 deepseek 且未配 EXTRA_BODY：自动注入 enable_thinking=false（LLM-GEN-005）")
    void deepseekPrimary_autoInjectThinking() throws IOException {
        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[]}";
        String result = intercept(
                newInterceptor("https://api.deepseek.com", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", ""),
                DEEPSEEK_URL, body);
        assertEquals("{\"model\":\"deepseek-v4-flash\",\"messages\":[],\"enable_thinking\":false}", result);
    }

    @Test
    @DisplayName("主 base-url 非 deepseek 且未配 EXTRA_BODY：不注入任何参数（LLM-GEN-006）")
    void nonDeepseekPrimary_noInjection() throws IOException {
        String body = "{\"model\":\"glm-4-plus\",\"messages\":[]}";
        String result = intercept(
                newInterceptor("https://open.bigmodel.cn/api/paas/v4", "https://api.moonshot.cn/v1", "", ""),
                GLM_URL, body);
        assertEquals(body, result);
    }

    @Test
    @DisplayName("显式配置主 EXTRA_BODY：按 JSON 原样注入（LLM-GEN-006）")
    void explicitPrimaryExtraBody_injected() throws IOException {
        String body = "{\"model\":\"glm-4-plus\",\"messages\":[]}";
        String result = intercept(
                newInterceptor("https://open.bigmodel.cn/api/paas/v4", "https://api.moonshot.cn/v1",
                        "{\"temperature_override\":1.2}", ""),
                GLM_URL, body);
        assertEquals("{\"model\":\"glm-4-plus\",\"messages\":[],\"temperature_override\":1.2}", result);
    }

    @Test
    @DisplayName("命中备 base-url：注入备 EXTRA_BODY（主备独立）")
    void backupBaseUrlHit_injectsBackupBody() throws IOException {
        String body = "{\"model\":\"qwen-plus\",\"messages\":[]}";
        String result = intercept(
                newInterceptor("https://api.deepseek.com", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "", "{\"enable_thinking\":false}"),
                BAILIAN_URL, body);
        assertEquals("{\"model\":\"qwen-plus\",\"messages\":[],\"enable_thinking\":false}", result);
    }

    @Test
    @DisplayName("URL 均不命中主/备 base-url：不注入")
    void unmatchedUrl_noInjection() throws IOException {
        String body = "{\"model\":\"x\",\"messages\":[]}";
        String result = intercept(
                newInterceptor("https://api.deepseek.com", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", ""),
                "https://other-provider.com/v1/chat/completions", body);
        assertEquals(body, result);
    }

    @Test
    @DisplayName("EXTRA_BODY 为非法 JSON：安全降级不注入不抛错（LLM-GEN-007）")
    void invalidExtraBody_safeDegradation() throws IOException {
        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[]}";
        String result = assertDoesNotThrow(() -> intercept(
                newInterceptor("https://api.deepseek.com", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "{invalid-json", ""),
                DEEPSEEK_URL, body));
        assertEquals(body, result);
    }

    @Test
    @DisplayName("请求体已含 enable_thinking：不重复注入（保留显式值）")
    void existingEnableThinking_notOverwritten() throws IOException {
        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[],\"enable_thinking\":true}";
        String result = intercept(
                newInterceptor("https://api.deepseek.com", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", ""),
                DEEPSEEK_URL, body);
        assertEquals(body, result);
    }

    @Test
    @DisplayName("非 chat/completions 路径：不处理直接放行")
    void nonChatPath_passthrough() throws IOException {
        String body = "{\"model\":\"deepseek-v4-flash\"}";
        String result = intercept(
                newInterceptor("https://api.deepseek.com", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", ""),
                "https://api.deepseek.com/v1/embeddings", body);
        assertEquals(body, result);
    }
}
