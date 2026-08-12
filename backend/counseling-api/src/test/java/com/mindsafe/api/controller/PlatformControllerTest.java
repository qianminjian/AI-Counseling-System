package com.mindsafe.api.controller;

import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.School;
import com.mindsafe.service.platform.PlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlatformController 单元测试（C3 重构后：仅验证 HTTP 编排，聚合逻辑已下沉 PlatformServiceTest）
 */
class PlatformControllerTest {

    private PlatformService platformService;
    private PlatformController controller;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        platformService = mock(PlatformService.class);
        controller = new PlatformController(platformService);
    }

    private Map<String, Object> overviewData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantCount", 1);
        m.put("schoolCount", 3L);
        m.put("studentCount", 120L);
        m.put("teacherCount", 8L);
        m.put("totalSessions", 500L);
        m.put("weeklySessions", 30L);
        m.put("totalAlerts", 15L);
        m.put("openAlerts", 2L);
        return m;
    }

    private Map<String, Object> tenantItem() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenantId);
        m.put("tenantCode", "SCH001");
        m.put("tenantName", "第一小学");
        m.put("status", "active");
        m.put("schoolCount", 1L);
        m.put("studentCount", 30L);
        m.put("teacherCount", 2L);
        m.put("sessionCount", 99L);
        return m;
    }

    @Test
    @DisplayName("getOverview 透传 PlatformService 聚合结果")
    void overview() {
        when(platformService.overview()).thenReturn(overviewData());

        var resp = controller.getOverview();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("tenantCount")).isEqualTo(1);
        assertThat(resp.data().get("schoolCount")).isEqualTo(3L);
        assertThat(resp.data().get("openAlerts")).isEqualTo(2L);
    }

    @Test
    @DisplayName("getTenants 透传租户统计列表")
    void tenants() {
        when(platformService.tenantStats()).thenReturn(List.of(tenantItem()));

        var resp = controller.getTenants();

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).get("tenantName")).isEqualTo("第一小学");
        assertThat(resp.data().get(0).get("sessionCount")).isEqualTo(99L);
    }

    @Test
    @DisplayName("getTenantDetail 租户不存在 → 404")
    void tenantDetail_notFound() {
        when(platformService.tenantDetail(tenantId)).thenReturn(null);

        assertThatThrownBy(() -> controller.getTenantDetail(tenantId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("租户不存在");
    }

    @Test
    @DisplayName("getTenantDetail 成功 → 透传详情")
    void tenantDetail_success() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tenant", Map.of("tenantId", tenantId));
        detail.put("schools", List.of());
        detail.put("dailySessionTrend", Map.of("2026-08-05", 2L));
        when(platformService.tenantDetail(tenantId)).thenReturn(detail);

        var resp = controller.getTenantDetail(tenantId);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("tenant")).isNotNull();
        assertThat((List<?>) resp.data().get("schools")).isEmpty();
    }

    @Test
    @DisplayName("getSchools 透传学校列表")
    void schools() {
        School s = new School();
        s.setSchoolId(UUID.randomUUID());
        s.setSchoolName("第一小学");
        when(platformService.schools()).thenReturn(List.of(s));

        var resp = controller.getSchools();

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).getSchoolName()).isEqualTo("第一小学");
    }
}
