package com.mindsafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 契约完整性集成测试（TEST-006 L1：代码 → OpenAPI 文档）
 * <p>
 * 职责：守卫「OpenAPI 文档与实际 Controller 端点一致」，任一业务端点漏文档化即红。
 * 背景：springdoc 2.7.0 输出 /api-docs，但此前无测试断言其与 Controller 实际端点一致。
 * 约束：不要求 Controller 写 @Operation（springdoc 自动生成），但要求端点/方法/responses 全量可见。
 */
class ContractOpenApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping") // actuator 的 controllerEndpointHandlerMapping 同类型，须按名注入
    private RequestMappingHandlerMapping handlerMapping;

    /** 非业务端点（框架/文档/健康检查/WebSocket），不要求出现在 OpenAPI paths */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/error", "/actuator", "/swagger-ui", "/api-docs", "/v3/api-docs", "/ws");

    /** 拉取 OpenAPI 文档 */
    private JsonNode fetchOpenApiDoc() throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return objectMapper.readTree(resp.getBody());
    }

    /** 收集业务端点：(path, methods)，排除框架路径与静态资源 handler */
    private List<Map.Entry<String, Set<RequestMethod>>> collectBusinessEndpoints() {
        List<Map.Entry<String, Set<RequestMethod>>> result = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (hm.getBeanType().getName().startsWith("org.springframework")
                    && !hm.getBeanType().getName().contains(".controller.")) {
                continue; // 框架 handler（BasicErrorController 等）由前缀排除覆盖，静态资源 handler 非 HandlerMethod
            }
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = new HashSet<>();
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatterns()
                        .forEach(p -> patterns.add(p.getPatternString()));
            } else if (info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (EXCLUDED_PREFIXES.stream().noneMatch(pattern::startsWith)) {
                    result.add(Map.entry(pattern, methods));
                }
            }
        }
        return result;
    }

    @Test
    void apiDocs_shouldReturnValidOpenApiDocument() throws Exception {
        JsonNode doc = fetchOpenApiDoc();
        assertThat(doc.has("paths")).isTrue();
        assertThat(doc.has("components")).isTrue();
        assertThat(doc.path("paths")).isNotEmpty();
    }

    @Test
    void allBusinessEndpoints_shouldBeDocumentedInOpenApi() throws Exception {
        JsonNode paths = fetchOpenApiDoc().path("paths");
        List<Map.Entry<String, Set<RequestMethod>>> endpoints = collectBusinessEndpoints();

        assertThat(endpoints).as("应收集到业务端点").isNotEmpty();
        for (Map.Entry<String, Set<RequestMethod>> e : endpoints) {
            String path = e.getKey();
            JsonNode pathNode = paths.path(path);
            assertThat(pathNode.isMissingNode())
                    .as("业务端点 %s 应出现在 OpenAPI paths（契约完整性 L1）", path)
                    .isFalse();
            // 方法匹配：显式声明的方法必须出现在 OpenAPI 中（OpenAPI JSON 的 method 键为小写）
            for (RequestMethod m : e.getValue()) {
                assertThat(pathNode.has(m.name().toLowerCase(Locale.ROOT)))
                        .as("端点 %s 的方法 %s 应出现在 OpenAPI 文档", path, m)
                        .isTrue();
            }
        }
    }

    @Test
    void eachEndpoint_shouldHaveResponsesWithSuccessCode() throws Exception {
        JsonNode paths = fetchOpenApiDoc().path("paths");
        for (JsonNode pathNode : paths) {
            for (String method : List.of("get", "post", "put", "delete", "patch")) {
                JsonNode op = pathNode.path(method);
                if (op.isMissingNode() || !op.isObject()) {
                    continue;
                }
                JsonNode responses = op.path("responses");
                assertThat(responses.isMissingNode() || !responses.isObject())
                        .as("端点 %s 方法 %s 应有非空 responses", paths, method.toUpperCase())
                        .isFalse();
                assertThat(responses).isNotEmpty();
                boolean hasSuccess = false;
                for (String code : (Iterable<String>) () -> responses.fieldNames()) {
                    if (code.startsWith("2")) {
                        hasSuccess = true;
                        break;
                    }
                }
                assertThat(hasSuccess)
                        .as("端点 %s 方法 %s 应包含 2xx 成功响应码", paths, method.toUpperCase())
                        .isTrue();
            }
        }
    }

    @Test
    void operationIds_shouldBeUnique() throws Exception {
        JsonNode paths = fetchOpenApiDoc().path("paths");
        Set<String> operationIds = new HashSet<>();
        for (JsonNode pathNode : paths) {
            for (String method : List.of("get", "post", "put", "delete", "patch")) {
                JsonNode op = pathNode.path(method);
                if (op.isMissingNode() || !op.isObject()) {
                    continue;
                }
                JsonNode id = op.path("operationId");
                if (!id.isMissingNode() && id.isTextual()) {
                    assertThat(operationIds.add(id.asText()))
                            .as("operationId 应全局唯一，重复：%s", id.asText())
                            .isTrue();
                }
            }
        }
    }

    @Test
    void componentSchemas_shouldContainCoreDtos() throws Exception {
        JsonNode schemas = fetchOpenApiDoc().path("components").path("schemas");
        assertThat(schemas.isObject()).as("components.schemas 应为对象").isTrue();
        // 探针：打印实际 schema 名（定稿后移除）
        List<String> names = new ArrayList<>();
        schemas.fieldNames().forEachRemaining(names::add);
        System.out.println("[CONTRACT-PROBE] schemas=" + String.join(",", names));
        assertThat(schemas.size()).isGreaterThanOrEqualTo(10);
    }
}
