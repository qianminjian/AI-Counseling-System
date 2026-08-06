package com.mindsafe.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * LLM 供应商专属参数通用化注入（doing/63：解绑 DeepSeek 专属硬编码，LLM-GEN-005/006/007）
 * <p>
 * 设计（替代原 DeepSeekThinkingConfig）：
 * - 主/备模型各自可配置 EXTRA_BODY（LLM_PRIMARY_EXTRA_BODY / LLM_BACKUP_EXTRA_BODY，JSON 字符串）
 * - 单个 RestClient 拦截器：请求 URI 命中主 base-url → 注入主 EXTRA_BODY；命中备 base-url → 注入备 EXTRA_BODY
 * - 行为兼容：EXTRA_BODY 未配置且命中 base-url 含 deepseek 时，自动注入 {"enable_thinking":false}
 *   （保持现状首 token 延迟优化，不再影响其他供应商）
 * - 请求体已含 enable_thinking 时保留显式值（不重复注入）
 * - JSON 解析失败安全降级（不注入、不阻断请求），日志留痕（C4）
 */
@Configuration
public class LlmExtraBodyConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmExtraBodyConfig.class);

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String primaryBaseUrl;

    @Value("${mindsafe.llm.backup.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String backupBaseUrl;

    @Value("${LLM_PRIMARY_EXTRA_BODY:}")
    private String primaryExtraBody;

    @Value("${LLM_BACKUP_EXTRA_BODY:}")
    private String backupExtraBody;

    /**
     * 注册 RestClient 拦截器（Spring AI 的 OpenAiApi 使用 RestClient.Builder 构建）
     */
    @Bean
    public RestClientCustomizer llmExtraBodyCustomizer(ObjectMapper objectMapper) {
        log.info("LLM 供应商专属参数注入器已注册（LlmExtraBodyConfig，主/备独立 EXTRA_BODY）");
        return builder -> builder.requestInterceptor(
                new ExtraBodyInterceptor(objectMapper, primaryBaseUrl, backupBaseUrl,
                        primaryExtraBody, backupExtraBody));
    }

    /**
     * 供应商专属参数注入拦截器（包私有：同包测试可直接构造）
     */
    static class ExtraBodyInterceptor implements ClientHttpRequestInterceptor {

        private final ObjectMapper objectMapper;
        private final String primaryBaseUrl;
        private final String backupBaseUrl;
        private final String primaryExtraBody;
        private final String backupExtraBody;

        ExtraBodyInterceptor(ObjectMapper objectMapper, String primaryBaseUrl, String backupBaseUrl,
                             String primaryExtraBody, String backupExtraBody) {
            this.objectMapper = objectMapper;
            this.primaryBaseUrl = primaryBaseUrl;
            this.backupBaseUrl = backupBaseUrl;
            this.primaryExtraBody = primaryExtraBody;
            this.backupExtraBody = backupExtraBody;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String path = request.getURI().getPath();
            if (path == null || !path.contains("/chat/completions") || body == null || body.length == 0) {
                return execution.execute(request, body);
            }

            String url = request.getURI().toString();
            String configuredExtra = null;
            boolean hitPrimary = false;
            boolean hitBackup = false;
            if (primaryBaseUrl != null && !primaryBaseUrl.isBlank() && url.startsWith(primaryBaseUrl)) {
                configuredExtra = primaryExtraBody;
                hitPrimary = true;
            } else if (backupBaseUrl != null && !backupBaseUrl.isBlank() && url.startsWith(backupBaseUrl)) {
                configuredExtra = backupExtraBody;
                hitBackup = true;
            }

            // 未显式配置 EXTRA_BODY：base-url 含 deepseek → 自动注入 enable_thinking=false（行为兼容）；否则不注入
            boolean autoInject = false;
            if (configuredExtra == null || configuredExtra.isBlank()) {
                String matched = hitPrimary ? primaryBaseUrl : (hitBackup ? backupBaseUrl : null);
                if (matched == null) {
                    return execution.execute(request, body); // 主/备均不命中，不注入
                }
                if (!matched.contains("deepseek")) {
                    return execution.execute(request, body); // 非 deepseek 且无显式配置，不注入
                }
                configuredExtra = "{\"enable_thinking\":false}";
                autoInject = true;
            }

            try {
                JsonNode node = objectMapper.readTree(body);
                if (!node.isObject()) {
                    return execution.execute(request, body);
                }
                ObjectNode obj = (ObjectNode) node;
                if (autoInject) {
                    // 请求体已含 enable_thinking → 保留显式值，不重复注入
                    if (obj.has("enable_thinking")) {
                        return execution.execute(request, body);
                    }
                    obj.put("enable_thinking", false);
                } else {
                    JsonNode extra = objectMapper.readTree(configuredExtra);
                    if (!extra.isObject()) {
                        log.debug("LLM extra body 跳过注入（EXTRA_BODY 非 JSON 对象）");
                        return execution.execute(request, body);
                    }
                    extra.fields().forEachRemaining(e -> obj.set(e.getKey(), e.getValue()));
                }
                byte[] newBody = objectMapper.writeValueAsBytes(obj);
                request.getHeaders().setContentLength(newBody.length);
                return execution.execute(request, newBody);
            } catch (Exception e) {
                // JSON 解析失败不阻断请求（安全降级），C4：留痕避免静默
                log.debug("LLM extra body 请求体重写失败（安全降级）: {}", e.getMessage());
                return execution.execute(request, body);
            }
        }
    }
}
