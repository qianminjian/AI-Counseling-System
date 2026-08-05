package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.safety.SosEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SosController 单元测试（P1 覆盖率冲刺：SOS 事件上报）
 */
class SosControllerTest {

    private SosEventService sosEventService;
    private SosController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private final UUID riskEventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sosEventService = mock(SosEventService.class);
        controller = new SosController(sosEventService);
    }

    private Authentication studentAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, studentUserId, "student"));
        return auth;
    }

    @Test
    @DisplayName("reportSosEvent 正常 → 落 S2 事件 + 返回 riskEventId")
    void reportSosEvent_success() {
        when(sosEventService.recordSosEvent(tenantId, studentUserId))
                .thenReturn(new SosEventService.SosResult(riskEventId, false));

        ApiResponse<Map<String, Object>> resp = controller.reportSosEvent(studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("deduplicated")).isEqualTo(false);
        assertThat(resp.data().get("riskEventId")).isEqualTo(riskEventId.toString());
        verify(sosEventService).recordSosEvent(tenantId, studentUserId);
    }

    @Test
    @DisplayName("reportSosEvent 5 分钟去重命中 → deduplicated=true")
    void reportSosEvent_deduplicated() {
        when(sosEventService.recordSosEvent(tenantId, studentUserId))
                .thenReturn(new SosEventService.SosResult(riskEventId, true));

        ApiResponse<Map<String, Object>> resp = controller.reportSosEvent(studentAuth());

        assertThat(resp.data().get("deduplicated")).isEqualTo(true);
        assertThat(resp.data().get("riskEventId")).isEqualTo(riskEventId.toString());
    }

    @Test
    @DisplayName("reportSosEvent riskEventId 为 null → 空串兜底")
    void reportSosEvent_nullEventId() {
        when(sosEventService.recordSosEvent(tenantId, studentUserId))
                .thenReturn(new SosEventService.SosResult(null, true));

        ApiResponse<Map<String, Object>> resp = controller.reportSosEvent(studentAuth());

        assertThat(resp.data().get("deduplicated")).isEqualTo(true);
        assertThat(resp.data().get("riskEventId")).isEqualTo("");
    }
}
