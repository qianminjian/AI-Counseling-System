package com.mindsafe.service.audit;

import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * AuditLogService 审计日志测试（BUG-AUDIT-01：detail 非 JSON 文本致 json 列落库失败——防御性归一化）
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
}
