package com.mindsafe.service.monitoring;

import com.mindsafe.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指标看板查询代理单元测试（ADMIN-P1-07，M2 关键指标看板）
 * 覆盖：白名单表达式放行 / 非白名单拒绝（PromQL 注入防护）/ 空表达式 / Prometheus 不可达降级
 */
class MetricsQueryServiceTest {

    private HttpServer server;
    private MetricsQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            String body = "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{\"__name__\":\"tts_synthesize_requests_total\"},\"value\":[1700000000,\"42\"]}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        service = new MetricsQueryService();
        // 通过反射设置 prometheusUrl 指向本地测试服务
        try {
            var field = MetricsQueryService.class.getDeclaredField("prometheusUrl");
            field.setAccessible(true);
            field.set(service, "http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("白名单指标表达式 → 代理查询返回 Prometheus 响应体")
    void whitelistedExprProxies() {
        Map<String, Object> body = service.query("tts_synthesize_requests_total");

        assertThat(body.get("status")).isEqualTo("success");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("result")).isNotNull();
    }

    @Test
    @DisplayName("白名单表达式 + label 过滤 → 放行")
    void whitelistedExprWithLabels() {
        Map<String, Object> body = service.query("tts_synthesize_requests_total{engine=\"cosyvoice\"}");

        assertThat(body.get("status")).isEqualTo("success");
    }

    @Test
    @DisplayName("sum() 聚合包裹（前端看板用）→ 放行")
    void sumWrappedExprAllowed() {
        Map<String, Object> body = service.query("sum(tts_synthesize_requests_total)");

        assertThat(body.get("status")).isEqualTo("success");
    }

    @Test
    @DisplayName("非白名单前缀（任意指标）→ 403 拒绝（防数据探取）")
    void nonWhitelistedPrefixRejected() {
        assertThatThrownBy(() -> service.query("node_cpu_seconds_total"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("PromQL 注入表达式（聚合/运算符）→ 403 拒绝")
    void promqlInjectionRejected() {
        assertThatThrownBy(() -> service.query("sum(rate(tts_synthesize_requests_total[5m]))"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
        assertThatThrownBy(() -> service.query("tts_foo{label=\"x\"} + tts_bar"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("畸形表达式（sum( 缺右括号 / 多余右括号）→ 403 拒绝（code-review L1）")
    void malformedSumRejected() {
        assertThatThrownBy(() -> service.query("sum(tts_synthesize_requests_total"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
        assertThatThrownBy(() -> service.query("tts_synthesize_requests_total)"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("Prometheus 不可达 → INTERNAL_ERROR（不挂死，明确报错）")
    void prometheusUnreachableRaises() {
        server.stop(0);

        assertThatThrownBy(() -> service.query("tts_synthesize_requests_total"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("空/空白表达式 → 参数错误")
    void blankExprRejected() {
        assertThatThrownBy(() -> service.query("  "))
                .isInstanceOf(BizException.class);
    }
}
