package com.mindsafe.api.controller;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlatformController 单元测试（P1 覆盖率冲刺：跨租户平台统计/租户详情/学校列表）
 */
class PlatformControllerTest {

    private TenantMapper tenantMapper;
    private SchoolMapper schoolMapper;
    private UserMapper userMapper;
    private CounselingSessionMapper sessionMapper;
    private RiskEventMapper riskEventMapper;
    private PlatformController controller;

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
        controller = new PlatformController(tenantMapper, schoolMapper, userMapper, sessionMapper, riskEventMapper);
    }

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setTenantId(tenantId);
        t.setTenantCode("SCH001");
        t.setTenantName("第一小学");
        t.setStatus("active");
        t.setCreatedAt(Instant.now());
        return t;
    }

    private School school() {
        School s = new School();
        s.setSchoolId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setSchoolName("第一小学");
        s.setStatus("active");
        return s;
    }

    @Test
    @DisplayName("getOverview 跨租户聚合全部指标")
    void overview() {
        when(tenantMapper.selectList(any())).thenReturn(List.of(tenant()));
        when(schoolMapper.selectCount(any())).thenReturn(3L);
        when(userMapper.selectCount(any())).thenReturn(120L).thenReturn(8L);
        when(sessionMapper.selectCount(any())).thenReturn(500L).thenReturn(30L);
        when(riskEventMapper.selectCount(any())).thenReturn(15L).thenReturn(2L);

        var resp = controller.getOverview();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("tenantCount")).isEqualTo(1);
        assertThat(resp.data().get("schoolCount")).isEqualTo(3L);
        assertThat(resp.data().get("studentCount")).isEqualTo(120L);
        assertThat(resp.data().get("teacherCount")).isEqualTo(8L);
        assertThat(resp.data().get("totalSessions")).isEqualTo(500L);
        assertThat(resp.data().get("weeklySessions")).isEqualTo(30L);
        assertThat(resp.data().get("totalAlerts")).isEqualTo(15L);
        assertThat(resp.data().get("openAlerts")).isEqualTo(2L);
    }

    @Test
    @DisplayName("getOverview 空租户 → 全 0")
    void overview_empty() {
        when(tenantMapper.selectList(any())).thenReturn(List.of());
        when(schoolMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(sessionMapper.selectCount(any())).thenReturn(0L);
        when(riskEventMapper.selectCount(any())).thenReturn(0L);

        var resp = controller.getOverview();

        assertThat(resp.data().get("tenantCount")).isEqualTo(0);
        assertThat(resp.data().get("openAlerts")).isEqualTo(0L);
    }

    @Test
    @DisplayName("getTenants 含各租户计数")
    void tenants() {
        when(tenantMapper.selectList(any())).thenReturn(List.of(tenant()));
        when(schoolMapper.selectCount(any())).thenReturn(1L);
        when(userMapper.selectCount(any())).thenReturn(30L).thenReturn(2L);
        when(sessionMapper.selectCount(any())).thenReturn(99L);

        var resp = controller.getTenants();

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).get("tenantName")).isEqualTo("第一小学");
        assertThat(resp.data().get(0).get("schoolCount")).isEqualTo(1L);
        assertThat(resp.data().get(0).get("studentCount")).isEqualTo(30L);
        assertThat(resp.data().get(0).get("teacherCount")).isEqualTo(2L);
        assertThat(resp.data().get(0).get("sessionCount")).isEqualTo(99L);
    }

    @Test
    @DisplayName("getTenants 空 → 空列表")
    void tenants_empty() {
        when(tenantMapper.selectList(any())).thenReturn(List.of());

        var resp = controller.getTenants();

        assertThat(resp.data()).isEmpty();
    }

    @Test
    @DisplayName("getTenantDetail 租户不存在 → 404")
    void tenantDetail_notFound() {
        when(tenantMapper.selectById(tenantId)).thenReturn(null);

        var resp = controller.getTenantDetail(tenantId);

        assertThat(resp.code()).isEqualTo(404);
        assertThat(resp.message()).isEqualTo("租户不存在");
    }

    @Test
    @DisplayName("getTenantDetail 成功 → 学校 + 近 7 天会话趋势")
    void tenantDetail_success() {
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant());
        when(schoolMapper.selectList(any())).thenReturn(List.of(school()));
        CounselingSession s1 = new CounselingSession();
        s1.setStartedAt(Instant.now());
        CounselingSession s2 = new CounselingSession();
        s2.setStartedAt(Instant.now());
        CounselingSession s3 = new CounselingSession();
        s3.setStartedAt(null);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s1, s2, s3));

        var resp = controller.getTenantDetail(tenantId);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("tenant")).isNotNull();
        assertThat((List<?>) resp.data().get("schools")).hasSize(1);
        // 2 条有 startedAt 且同一天 → 当日计数 2；null 被过滤
        assertThat(((java.util.Map<?, ?>) resp.data().get("dailySessionTrend")).size()).isEqualTo(1);
        assertThat(((java.util.Map<?, ?>) resp.data().get("dailySessionTrend")).containsValue(2L)).isTrue();
    }

    @Test
    @DisplayName("getTenantDetail 无会话 → 空趋势")
    void tenantDetail_noSessions() {
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant());
        when(schoolMapper.selectList(any())).thenReturn(List.of());
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        var resp = controller.getTenantDetail(tenantId);

        assertThat(((java.util.Map<?, ?>) resp.data().get("dailySessionTrend"))).isEmpty();
    }

    @Test
    @DisplayName("getSchools 返回全部学校")
    void schools() {
        when(schoolMapper.selectList(any())).thenReturn(List.of(school()));

        var resp = controller.getSchools();

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).getSchoolName()).isEqualTo("第一小学");
    }
}
