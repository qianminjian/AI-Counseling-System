package com.mindsafe.service.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 企微 Webhook 告警实现（OPS-004）
 * <p>
 * 配置：mindsafe.alert.wecom.webhook-url=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
 * 未配置时自动降级为 LoggingAlertService。
 */
@Service
@ConditionalOnProperty(name = "mindsafe.alert.wecom.webhook-url")
public class WeComAlertService implements AlertService {

    private static final Logger log = LoggerFactory.getLogger(WeComAlertService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final String webhookUrl;

    private final RestTemplate restTemplate = buildRestTemplate();

    // BA-08（DOC-074）：@Value 私有字段改构造器注入（消除测试反射 setField；mentionedList 字段零消费已删）
    public WeComAlertService(@Value("${mindsafe.alert.wecom.webhook-url}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /** 告警外呼必须带超时：企微不可达时不能无限占用 @Async 线程 */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }

    @Async
    @Override
    public void sendAlert(AlertLevel level, String title, String detail) {
        try {
            String emoji = switch (level) {
                case CRITICAL -> "🔴";
                case WARNING -> "🟡";
                case INFO -> "🔵";
            };

            String content = String.format("""
                    %s **MindSafe 告警** [%s]
                    > **标题**：%s
                    > **时间**：%s
                    > **详情**：%s
                    """, emoji, level, title, FMT.format(Instant.now()), detail);

            Map<String, Object> body = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("content", content)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(webhookUrl, request, String.class);
            log.info("企微告警发送成功: level={}, title={}", level, title);
        } catch (Exception e) {
            log.warn("企微告警发送失败（降级为日志）: title={}, error={}", title, e.getMessage());
            log.error("[ALERT-{}] {}: {}", level, title, detail);
        }
    }
}
