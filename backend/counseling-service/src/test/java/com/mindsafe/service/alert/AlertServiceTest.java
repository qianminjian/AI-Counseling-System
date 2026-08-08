package com.mindsafe.service.alert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AlertService 实现单元测试（13/20 篇审计补齐：service.alert 0%→80%）
 * 覆盖：WeComAlertService 三级告警 webhook 报文结构、发送失败降级不抛异常；
 * LoggingAlertService 三级日志降级不抛异常
 */
class AlertServiceTest {

    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedContentTypes = new CopyOnWriteArrayList<>();
    private int port;

    private WeComAlertService weComAlertService;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(body, StandardCharsets.UTF_8));
            receivedContentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            byte[] resp = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        port = server.getAddress().getPort();

        // BA-08：构造器注入（webhookUrl 已 final，测试直接传本地地址）
        weComAlertService = new WeComAlertService("http://127.0.0.1:" + port + "/webhook");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("CRITICAL 告警：markdown 报文含 🔴、级别、标题、详情，Content-Type=JSON")
    void criticalAlertPayload() {
        weComAlertService.sendAlert(AlertService.AlertLevel.CRITICAL, "LLM 熔断", "错误率超过阈值 50%");

        assertThat(receivedBodies).hasSize(1);
        String body = receivedBodies.get(0);
        assertThat(body).contains("msgtype").contains("markdown");
        assertThat(body).contains("\\uD83D\\uDD34"); // 🔴（Jackson 非 ASCII 转义）
        assertThat(body).contains("CRITICAL");
        assertThat(body).contains("LLM 熔断");
        assertThat(body).contains("错误率超过阈值 50%");
        assertThat(body).contains("时间");
        assertThat(receivedContentTypes.get(0)).startsWith("application/json");
    }

    @Test
    @DisplayName("WARNING/INFO 告警：对应 🟡/🔵 表情与级别标识")
    void warningAndInfoAlertPayload() {
        weComAlertService.sendAlert(AlertService.AlertLevel.WARNING, "SLA 超时", "预警 30 分钟未认领");
        weComAlertService.sendAlert(AlertService.AlertLevel.INFO, "服务重启", "counseling-app 重启完成");

        assertThat(receivedBodies).hasSize(2);
        assertThat(receivedBodies.get(0)).contains("\\uD83D\\uDFE1").contains("WARNING").contains("SLA 超时");
        assertThat(receivedBodies.get(1)).contains("\\uD83D\\uDD35").contains("INFO").contains("服务重启");
    }

    @Test
    @DisplayName("webhook 不可达 → 降级为日志，不抛异常（告警链不因外呼失败中断）")
    void unreachableWebhookDegradesGracefully() {
        weComAlertService = new WeComAlertService("http://127.0.0.1:1/webhook");

        assertThatCode(() -> weComAlertService.sendAlert(
                AlertService.AlertLevel.CRITICAL, "测试降级", "webhook 不可达"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LoggingAlertService：三级告警均不抛异常（兜底实现）")
    void loggingAlertServiceAllLevels() {
        LoggingAlertService loggingService = new LoggingAlertService();

        assertThatCode(() -> {
            loggingService.sendAlert(AlertService.AlertLevel.CRITICAL, "c", "d");
            loggingService.sendAlert(AlertService.AlertLevel.WARNING, "w", "d");
            loggingService.sendAlert(AlertService.AlertLevel.INFO, "i", "d");
        }).doesNotThrowAnyException();
    }
}
