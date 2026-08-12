package com.mindsafe.service.audit;

import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogService 审计日志测试（BUG-AUDIT-01：detail 非 JSON 文本致 json 列落库失败——防御性归一化；
 * 专题 D：P0-1 系统级审计 tenantId=null 落库语义、P0-3 请求上下文捕获 IP 哈希/UA）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService 审计日志")
class AuditLogServiceTest {

    @Mock private AuditLogMapper auditLogMapper;

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        service = new AuditLogService(auditLogMapper);
    }

    @Test
    @DisplayName("BUG-AUDIT-01: 普通文本 detail 自动包装为 JSON {\"message\":...}（防 json 列落库失败）")
    void normalizeDetail_wrapsPlainText() {
        String result = AuditLogService.normalizeDetail("监护人同意确认（PIPL §31），版本 v1");
        assertThat(result).startsWith("{\"message\":\"");
        assertThat(result).contains("监护人同意确认");
    }

    @Test
    @DisplayName("合法 JSON detail 原样透传（不二次包装）")
    void normalizeDetail_keepsValidJson() {
        String json = "{\"created\":52,\"ingested\":50}";
        assertThat(AuditLogService.normalizeDetail(json)).isEqualTo(json);
    }

    @Test
    @DisplayName("null/空白 detail 原样透传")
    void normalizeDetail_nullAndBlank() {
        assertThat(AuditLogService.normalizeDetail(null)).isNull();
        assertThat(AuditLogService.normalizeDetail("  ")).isEqualTo("  ");
    }

    @Test
    @DisplayName("log() 落库前应用归一化（普通文本 → JSON detail）")
    void log_appliesNormalization() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.log(tenantId, userId, "GUARDIAN_CONSENT", "user", userId, "监护人同意确认（PIPL §31）");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).startsWith("{\"message\":\"");
    }

    @Test
    @DisplayName("D-P0-1: 系统级审计（tenantId=null）可正常组装落库（V47 迁移放开 NOT NULL 后 DB 允许）")
    void log_systemLevelAuditTenantNull() {
        service.log(null, null, "DATA_RETENTION_CLEANUP", "system", null, "定期清理: 删除消息摘要 0 条, 删除会话 0 条");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog inserted = captor.getValue();
        assertThat(inserted.getTenantId()).isNull();
        assertThat(inserted.getUserId()).isNull();
        assertThat(inserted.getAction()).isEqualTo("DATA_RETENTION_CLEANUP");
        assertThat(inserted.getResourceType()).isEqualTo("system");
    }

    @Test
    @DisplayName("D-P0-3: 调用方同步线程已捕获请求上下文时，审计记录含 IP 哈希与 User-Agent")
    void log_capturesRequestContext() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // 模拟调用方同步线程持有的请求上下文（TaskDecorator 将其传播到 @Async 审计线程）
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.9, 203.0.113.7");
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0 mindsafe-test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            service.log(tenantId, userId, "LOGIN", "user", userId, null);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog inserted = captor.getValue();
        // ClientIpResolver 最右不可伪造语义：XFF 取最右段 203.0.113.7 做 SHA-256 短哈希
        assertThat(inserted.getIpHash()).hasSize(16).isNotEqualTo("unknown");
        assertThat(inserted.getUserAgent()).isEqualTo("Mozilla/5.0 mindsafe-test");
    }

    @Test
    @DisplayName("D-P0-3: 无请求上下文时审计记录 ipHash/userAgent 保持 null（系统级/定时任务场景）")
    void log_withoutRequestContextKeepsNull() {
        service.log(null, null, "DATA_RETENTION_WITHDRAWAL_CLEANUP", "system", null, "撤回学生清理");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getIpHash()).isNull();
        assertThat(captor.getValue().getUserAgent()).isNull();
    }
}
