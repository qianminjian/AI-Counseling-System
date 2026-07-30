package com.mindsafe.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * DeepSeek V4 性能优化：关闭思考模式（降低首 token 延迟 5-10s → 1-3s）
 * <p>
 * DeepSeek V4 默认启用思考模式（模型先内部推理再输出），对心理辅导对话场景不必要。
 * 通过 HTTP 拦截器在请求体中注入 "enable_thinking": false，关闭思考模式。
 * <p>
 * 原理：Spring AI 使用 RestClient 调用 OpenAI 兼容 API，
 * RestClientCustomizer 注入的拦截器会修改发往 DeepSeek 的请求体。
 */
@Configuration
public class DeepSeekThinkingConfig {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekThinkingConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * HTTP 拦截器：为 DeepSeek chat/completions 请求注入 enable_thinking=false
     */
    static class DisableThinkingInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String path = request.getURI().getPath();
            if (path != null && path.contains("/chat/completions") && body != null && body.length > 0) {
                try {
                    JsonNode node = MAPPER.readTree(body);
                    if (node.isObject() && !node.has("enable_thinking")) {
                        ObjectNode obj = (ObjectNode) node;
                        obj.put("enable_thinking", false);
                        body = MAPPER.writeValueAsBytes(obj);
                        request.getHeaders().setContentLength(body.length);
                    }
                } catch (Exception e) {
                    // JSON 解析失败不阻断请求（安全降级）
                }
            }
            return execution.execute(request, body);
        }
    }

    /**
     * 注册 RestClient 拦截器（Spring AI 的 OpenAiApi 使用 RestClient.Builder 构建）
     */
    @Bean
    public RestClientCustomizer deepSeekThinkingCustomizer() {
        log.info("DeepSeek V4 思考模式已关闭（enable_thinking=false），首 token 延迟优化");
        return builder -> builder.requestInterceptor(new DisableThinkingInterceptor());
    }
}
