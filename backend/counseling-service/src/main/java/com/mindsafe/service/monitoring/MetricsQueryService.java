package com.mindsafe.service.monitoring;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 指标看板查询代理（ADMIN-P1-07，M2 关键指标看板）
 * <p>
 * 后端代理 Prometheus HTTP API（/api/v1/query 即时查询），管理端不直连 9090
 * （避免暴露端口、统一鉴权、统一 CORS——doing/83 §5.2 关键设计决策）。
 * 安全：白名单前缀校验——仅允许平台自有指标前缀 + 简单 label 过滤的表达式，
 * 非白名单表达式拒绝（403），防 PromQL 注入与任意数据探取。
 * 设计见 doing/83 后台管理端 §5.2（2.2 关键指标看板）/§13.2（ADMIN-P1-07）。
 */
@Service
public class MetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(MetricsQueryService.class);

    /** 白名单指标前缀（design/03 §8 指标表 + 降级监控文档 §3.1） */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "mindsafe_",      // LLM/TTS/voice 业务指标（含降级指标）
            "tts_",           // tts-service 指标
            "voice_",         // voice-service 指标
            "http_server_requests", // API 响应时间（后端）
            "jvm_",           // JVM 指标
            "hikaricp_",      // 数据库连接池
            "process_",       // 进程指标
            "system_",        // 系统指标
            "logback_"        // 日志指标
    );

    /**
     * 表达式形态：可选单层 sum() 聚合包裹 + 指标名（前缀匹配）+ 可选简单 label 过滤。
     * sum( 与 ) 必须配对（code-review L1：杜绝 sum(x 缺右括号的畸形表达式）。
     * 不允许 rate/range/运算符/多指标（防 PromQL 注入与任意数据探取）。
     */
    private static final Pattern EXPR_PATTERN = Pattern.compile(
            "^(?:sum\\([a-zA-Z_][a-zA-Z0-9_]*(?:\\{[^{}]*\\})?\\)|[a-zA-Z_][a-zA-Z0-9_]*(?:\\{[^{}]*\\})?)$");

    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${mindsafe.monitoring.prometheus-url:http://prometheus:9090}")
    private String prometheusUrl;

    /**
     * 白名单校验 + 代理查询（即时向量）。
     *
     * @param expr 指标表达式（单指标名 + 可选 label 过滤）
     * @return Prometheus /api/v1/query 原始响应体（status/data 结构透传）
     */
    public Map<String, Object> query(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "expr 必填（指标表达式）");
        }
        String trimmed = expr.trim();
        if (!isAllowed(trimmed)) {
            log.warn("指标查询被拒（非白名单表达式）: {}", trimmed);
            throw new BizException(ErrorCode.FORBIDDEN, "表达式不在白名单：仅允许平台自有指标名 + 简单 label 过滤");
        }
        try {
            // code-review M1：URLEncoder 空格编码为 +（form 语义），RestTemplate 链会将其二次编码为 %2B
            // 导致 Prometheus 收到字面 + 查询失败——替换为 %20 规避（字面 + 已被编码为 %2B，不会误替换）
            String encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8).replace("+", "%20");
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.getForObject(
                    prometheusUrl + "/api/v1/query?query=" + encoded, Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientException e) {
            log.warn("Prometheus 查询失败: expr={}, error={}", trimmed, e.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Prometheus 查询失败: " + e.getMessage());
        }
    }

    /** 白名单判定：表达式形态合法 + 指标名前缀在白名单（剥离 sum() 包裹后取指标名） */
    private boolean isAllowed(String expr) {
        if (!EXPR_PATTERN.matcher(expr).matches()) {
            return false;
        }
        String inner = expr.startsWith("sum(") ? expr.substring(4, expr.length() - 1) : expr;
        String metricName = inner.split("\\{", 2)[0];
        return ALLOWED_PREFIXES.stream().anyMatch(metricName::startsWith);
    }

    /** 查询外呼必须带超时：Prometheus 不可达时不能挂死请求线程（OpsService 同模式） */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
