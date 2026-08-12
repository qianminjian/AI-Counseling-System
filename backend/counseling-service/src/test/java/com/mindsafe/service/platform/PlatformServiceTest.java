package com.mindsafe.service.platform;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.School;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.SchoolMapper;
import com.mindsafe.domain.mapper.TenantMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C3（2026-08-05）：PlatformController 过厚重构——跨租户统计聚合下沉 service。
 * <p>
 * 原 controller 直插 5 个 mapper、17 处查询（审计：controller 越过 service 直插 mapper 15/31）。
 * 本测试锁定 PlatformService 的聚合行为，controller 仅保留 HTTP 编排。
 */
class PlatformServiceTest {

    private TenantMapper tenantMapper;
    private SchoolMapper schoolMapper;
    private UserMapper userMapper;
    private CounselingSessionMapper sessionMapper;
    private RiskEventMapper riskEventMapper;
    private PlatformService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Tenant.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), School.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), CounselingSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), RiskEvent.class);

        tenantMapper = mock(TenantMapper.class);
        schoolMapper = mock(SchoolMapper.class);
        userMapper = mock(UserMapper.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        riskEventMapper = mock(RiskEventMapper.class);
        service = new PlatformService(tenantMapper, schoolMapper, userMapper, sessionMapper, riskEventMapper);
    }

    private Tenant tenant(UUID id, String code, String name, String status) {
        Tenant t = new Tenant();
        t.setTenantId(id);
        t.setTenantCode(code);
        t.setTenantName(name);
        t.setStatus(status);
        t.setCreatedAt(Instant.now());
        return t;
    }

    @Test
    @DisplayName("overview：聚合租户/学校/学生/教师/会话/风险统计")
    void overviewAggregatesAllCounts() {
        when(tenantMapper.selectList(any())).thenReturn(List.of(
                tenant(UUID.randomUUID(), "A", "甲校", Tenant.STATUS_ACTIVE),
                tenant(UUID.randomUUID(), "B", "乙校", Tenant.STATUS_SUSPENDED)));
        when(schoolMapper.selectCount(any())).thenReturn(3L);
        when(userMapper.selectCount(any())).thenReturn(120L, 10L, 99L);
        when(sessionMapper.selectCount(any())).thenReturn(500L, 42L);
        when(riskEventMapper.selectCount(any())).thenReturn(7L, 2L);

        Map<String, Object> overview = service.overview();

        assertThat(overview).containsEntry("tenantCount", 2)
                .containsEntry("schoolCount", 3L)
                .containsEntry("studentCount", 120L)
                .containsEntry("teacherCount", 10L)
                .containsEntry("totalSessions", 500L)
                .containsEntry("weeklySessions", 42L)
                .containsEntry("totalAlerts", 7L)
                .containsEntry("openAlerts", 2L);
        // 活跃租户过滤
        verify(tenantMapper).selectList(any());
    }

    @Test
    @DisplayName("tenantStats：GROUP BY 聚合一次取回，每租户填充学校/学生/教师/会话数")
    void tenantStatsAggregatesPerTenant() {
        Tenant a = tenant(tenantId, "A", "甲校", Tenant.STATUS_ACTIVE);
        when(tenantMapper.selectList(any())).thenReturn(List.of(a));
        // P1-7：selectMaps 聚合行（key 为小写物理列名 tenant_id/cnt）
        when(schoolMapper.selectMaps(any())).thenReturn(List.of(Map.of("tenant_id", tenantId, "cnt", 1L)));
        when(userMapper.selectMaps(any())).thenReturn(
                List.of(Map.of("tenant_id", tenantId, "cnt", 20L)),
                List.of(Map.of("tenant_id", tenantId, "cnt", 3L)));
        when(sessionMapper.selectMaps(any())).thenReturn(List.of(Map.of("tenant_id", tenantId, "cnt", 30L)));

        List<Map<String, Object>> stats = service.tenantStats();

        assertThat(stats).hasSize(1);
        Map<String, Object> item = stats.get(0);
        assertThat(item).containsEntry("tenantId", tenantId)
                .containsEntry("tenantCode", "A")
                .containsEntry("tenantName", "甲校")
                .containsEntry("schoolCount", 1L)
                .containsEntry("studentCount", 20L)
                .containsEntry("teacherCount", 3L)
                .containsEntry("sessionCount", 30L);
    }

    @Test
    @DisplayName("tenantStats：无数据租户各项计数回落 0（聚合行缺失不 NPE）")
    void tenantStatsDefaultsZeroWhenNoAggregateRows() {
        Tenant a = tenant(tenantId, "A", "甲校", Tenant.STATUS_ACTIVE);
        when(tenantMapper.selectList(any())).thenReturn(List.of(a));
        when(schoolMapper.selectMaps(any())).thenReturn(List.of());
        when(userMapper.selectMaps(any())).thenReturn(List.of());
        when(sessionMapper.selectMaps(any())).thenReturn(List.of());

        List<Map<String, Object>> stats = service.tenantStats();

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0))
                .containsEntry("schoolCount", 0L)
                .containsEntry("studentCount", 0L)
                .containsEntry("teacherCount", 0L)
                .containsEntry("sessionCount", 0L);
    }

    @Test
    @DisplayName("tenantDetail：返回租户+学校+近7天会话趋势；租户不存在返回 null")
    void tenantDetailReturnsDetailOrNull() {
        Tenant t = tenant(tenantId, "A", "甲校", Tenant.STATUS_ACTIVE);
        when(tenantMapper.selectById(tenantId)).thenReturn(t);
        when(schoolMapper.selectList(any())).thenReturn(List.of(new School()));
        CounselingSession recent = new CounselingSession();
        recent.setStartedAt(Instant.now());
        when(sessionMapper.selectList(any())).thenReturn(List.of(recent));

        Map<String, Object> detail = service.tenantDetail(tenantId);
        assertThat(detail).containsKey("tenant").containsKey("schools").containsKey("dailySessionTrend");
        @SuppressWarnings("unchecked")
        Map<String, Long> trend = (Map<String, Long>) detail.get("dailySessionTrend");
        assertThat(trend).hasSize(1);

        when(tenantMapper.selectById(UUID.randomUUID())).thenReturn(null);
        assertThat(service.tenantDetail(UUID.randomUUID())).isNull();
    }

    @Test
    @DisplayName("schools：跨租户学校列表按创建时间倒序")
    void schoolsReturnsAllSchools() {
        when(schoolMapper.selectList(any())).thenReturn(List.of(new School()));
        assertThat(service.schools()).hasSize(1);
    }
}
