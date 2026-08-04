package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.PromptVersion;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.PromptVersionMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.prompt.PromptEvalGovernance;
import com.mindsafe.service.prompt.PromptEvalScoreReader;
import com.mindsafe.service.prompt.PromptVersionService;
import com.mindsafe.service.prompt.TemplateMatrixRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminPromptController 单元测试（P1 覆盖率冲刺：版本 CRUD / 激活门禁 / A/B 对比 / 放量评估）
 */
class AdminPromptControllerTest {

    private PromptVersionService promptVersionService;
    private PromptVersionMapper promptVersionMapper;
    private CounselingSessionMapper sessionMapper;
    private QualityScoreMapper qualityScoreMapper;
    private AuditLogService auditLogService;
    private TemplateMatrixRegistry templateMatrixRegistry;
    private PromptEvalGovernance promptEvalGovernance;
    private PromptEvalScoreReader evalScoreReader;
    private AdminPromptController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PromptVersion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), CounselingSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), QualityScore.class);

        promptVersionService = mock(PromptVersionService.class);
        promptVersionMapper = mock(PromptVersionMapper.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        qualityScoreMapper = mock(QualityScoreMapper.class);
        auditLogService = mock(AuditLogService.class);
        templateMatrixRegistry = mock(TemplateMatrixRegistry.class);
        promptEvalGovernance = mock(PromptEvalGovernance.class);
        evalScoreReader = mock(PromptEvalScoreReader.class);
        controller = new AdminPromptController(promptVersionService, promptVersionMapper, sessionMapper,
                qualityScoreMapper, auditLogService, templateMatrixRegistry, promptEvalGovernance,
                evalScoreReader);
    }

    private Authentication adminAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, adminUserId, "admin"));
        return auth;
    }

    private PromptVersion version(String tag) {
        PromptVersion pv = new PromptVersion();
        pv.setVersionId(versionId);
        pv.setTenantId(tenantId);
        pv.setTemplateKey("SYS_001");
        pv.setVersion(3);
        pv.setContent("你好，我是小安");
        pv.setDescription("v3 优化");
        pv.setAbGroup("control");
        pv.setIsActive(true);
        pv.setCreatedBy(adminUserId);
        pv.setCreatedAt(Instant.now());
        return pv;
    }

    private CounselingSession session(String versionTag) {
        CounselingSession s = new CounselingSession();
        s.setSessionId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setPromptVersion(versionTag);
        return s;
    }

    private QualityScore score(BigDecimal empathy, BigDecimal cbt, BigDecimal safety, BigDecimal engagement) {
        QualityScore q = new QualityScore();
        q.setSessionId(UUID.randomUUID());
        q.setEmpathyScore(empathy);
        q.setCbtCompletion(cbt);
        q.setSafetyCompliance(safety);
        q.setEngagementScore(engagement);
        return q;
    }

    // ===== 版本 CRUD =====

    @Test
    @DisplayName("listVersions 透传 tenantId 与 templateKey")
    void listVersions() {
        when(promptVersionService.listVersions(tenantId, "SYS_001")).thenReturn(List.of(version("SYS_001:v3:control")));

        ApiResponse<List<Map<String, Object>>> resp = controller.listVersions("SYS_001", tenantId);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).get("templateKey")).isEqualTo("SYS_001");
        assertThat(resp.data().get(0).get("abGroup")).isEqualTo("control");
        assertThat(resp.data().get(0).get("contentLength")).isEqualTo("你好，我是小安".length());
    }

    @Test
    @DisplayName("listVersions tenantId 为空透传 null（全局模板）")
    void listVersions_nullTenant() {
        when(promptVersionService.listVersions(null, "SYS_001")).thenReturn(List.of());

        var resp = controller.listVersions("SYS_001", null);

        assertThat(resp.data()).isEmpty();
        verify(promptVersionService).listVersions(null, "SYS_001");
    }

    @Test
    @DisplayName("getVersion 版本存在 → 详情 map")
    void getVersion_found() {
        when(promptVersionMapper.selectById(versionId)).thenReturn(version("SYS_001:v3:control"));

        var resp = controller.getVersion(versionId);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("versionId")).isEqualTo(versionId);
        assertThat(resp.data().get("isActive")).isEqualTo(true);
    }

    @Test
    @DisplayName("getVersion 版本不存在 → error")
    void getVersion_notFound() {
        when(promptVersionMapper.selectById(versionId)).thenReturn(null);

        var resp = controller.getVersion(versionId);

        assertThat(resp.data().get("error")).isEqualTo("版本不存在");
    }

    @Test
    @DisplayName("createVersion 成功 → 缺省 abGroup=control、tenantId 从 body 解析")
    void createVersion_success() {
        when(promptVersionService.createVersion(tenantId, "SYS_001", "内容", "描述", "control", adminUserId))
                .thenReturn(version("SYS_001:v3:control"));

        var resp = controller.createVersion(Map.of(
                "templateKey", "SYS_001",
                "content", "内容",
                "description", "描述",
                "tenantId", tenantId.toString()), adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("templateKey")).isEqualTo("SYS_001");
    }

    @Test
    @DisplayName("createVersion 缺 templateKey → error")
    void createVersion_missingKey() {
        var resp = controller.createVersion(Map.of("content", "内容"), adminAuth());

        assertThat(resp.data().get("error")).isEqualTo("templateKey 和 content 为必填项");
        verify(promptVersionService, never()).createVersion(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createVersion 缺 content → error")
    void createVersion_missingContent() {
        var resp = controller.createVersion(Map.of("templateKey", "SYS_001"), adminAuth());

        assertThat(resp.data().get("error")).isEqualTo("templateKey 和 content 为必填项");
    }

    @Test
    @DisplayName("createVersion 无 tenantId → 全局版本")
    void createVersion_noTenant() {
        when(promptVersionService.createVersion(null, "SYS_001", "内容", null, "control", adminUserId))
                .thenReturn(version("SYS_001:v3:control"));

        controller.createVersion(Map.of("templateKey", "SYS_001", "content", "内容"), adminAuth());

        verify(promptVersionService).createVersion(null, "SYS_001", "内容", null, "control", adminUserId);
    }

    @Test
    @DisplayName("createVersion abGroup 自定义透传")
    void createVersion_customAbGroup() {
        when(promptVersionService.createVersion(eq(tenantId), eq("SYS_001"), eq("内容"), eq(null),
                eq("treatment_a"), eq(adminUserId))).thenReturn(version("SYS_001:v3:treatment_a"));

        controller.createVersion(Map.of("templateKey", "SYS_001", "content", "内容",
                "abGroup", "treatment_a", "tenantId", tenantId.toString()), adminAuth());

        verify(promptVersionService).createVersion(tenantId, "SYS_001", "内容", null, "treatment_a", adminUserId);
    }

    // ===== 激活门禁 =====

    @Test
    @DisplayName("activateVersion reviewer 为空 → PARAM_INVALID")
    void activate_missingReviewer() {
        var resp = controller.activateVersion(versionId, Map.of("reviewer", "  "), adminAuth());

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        verify(promptVersionService, never()).activateVersion(any(), any());
    }

    @Test
    @DisplayName("activateVersion 成功 → 审计 reviewer 签字")
    void activate_success() {
        var resp = controller.activateVersion(versionId, Map.of("reviewer", "张老师"), adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        verify(promptVersionService).activateVersion(versionId, "张老师");
        verify(auditLogService).log(tenantId, adminUserId, "PROMPT_ACTIVATE", "prompt_version", versionId,
                "reviewer=张老师");
    }

    @Test
    @DisplayName("activateVersion 请求体为空 → 等价缺 reviewer")
    void activate_nullBody() {
        var resp = controller.activateVersion(versionId, null, adminAuth());

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("activateVersion 版本不存在 → RESOURCE_NOT_FOUND")
    void activate_notFound() {
        doThrow(new IllegalArgumentException("版本不存在")).when(promptVersionService).activateVersion(versionId, "张老师");

        var resp = controller.activateVersion(versionId, Map.of("reviewer", "张老师"), adminAuth());

        assertThat(resp.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
    }

    @Test
    @DisplayName("activateVersion 门禁拒绝 → PARAM_INVALID 且不写审计")
    void activate_gateRejected() {
        doThrow(new IllegalStateException("红队回归未通过")).when(promptVersionService).activateVersion(versionId, "张老师");

        var resp = controller.activateVersion(versionId, Map.of("reviewer", "张老师"), adminAuth());

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).contains("红队回归未通过");
        verify(auditLogService, never()).log(any(), any(), eq("PROMPT_ACTIVATE"), any(), any(), any());
    }

    // ===== 停用 =====

    @Test
    @DisplayName("deactivateVersion 存在 → 停用 + 缓存失效")
    void deactivate_found() {
        PromptVersion pv = version("SYS_001:v3:control");
        when(promptVersionMapper.selectById(versionId)).thenReturn(pv);

        var resp = controller.deactivateVersion(versionId);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(pv.getIsActive()).isFalse();
        verify(promptVersionMapper).<PromptVersion>updateById(pv);
        verify(promptVersionService).invalidateCache();
    }

    @Test
    @DisplayName("deactivateVersion 不存在 → 静默跳过")
    void deactivate_notFound() {
        when(promptVersionMapper.selectById(versionId)).thenReturn(null);

        controller.deactivateVersion(versionId);

        verify(promptVersionMapper, never()).<PromptVersion>updateById(any(PromptVersion.class));
        verify(promptVersionService, never()).invalidateCache();
    }

    // ===== A/B 对比 =====

    @Test
    @DisplayName("abComparison 无会话 → 空分组")
    void abComparison_empty() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        var resp = controller.abComparison(tenantId, null);

        assertThat(resp.data().get("totalSessions")).isEqualTo(0);
        assertThat((List<?>) resp.data().get("groups")).isEmpty();
    }

    @Test
    @DisplayName("abComparison 按 tag 尾段分组并计算均值（含 null 过滤）")
    void abComparison_groups() {
        CounselingSession s1 = session("SYS_001:v3:treatment_a");
        CounselingSession s2 = session("SYS_001:v3:treatment_a");
        when(sessionMapper.selectList(any())).thenReturn(List.of(s1, s2));
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(
                score(new BigDecimal("80"), new BigDecimal("70"), new BigDecimal("90"), new BigDecimal("60")),
                score(new BigDecimal("100"), null, new BigDecimal("50"), new BigDecimal("80"))));

        var resp = controller.abComparison(tenantId, "SYS_001");

        Map<String, Object> result = resp.data();
        assertThat(result.get("totalSessions")).isEqualTo(2);
        List<?> groups = (List<?>) result.get("groups");
        assertThat(groups).hasSize(1);
        Map<String, Object> group = (Map<String, Object>) groups.get(0);
        assertThat(group.get("abGroup")).isEqualTo("treatment_a");
        assertThat(group.get("sessionCount")).isEqualTo(2);
        assertThat(group.get("scoredCount")).isEqualTo(2);
        // 均值: empathy=(80+100)/2=90, cbt=70（null 过滤后仅1个）, safety=70, engagement=70
        assertThat(group.get("avgEmpathy")).isEqualTo(90.0);
        assertThat(group.get("avgCbtCompletion")).isEqualTo(70.0);
        assertThat(group.get("avgSafetyCompliance")).isEqualTo(70.0);
        assertThat(group.get("avgEngagement")).isEqualTo(70.0);
        // overall=(90+70+70+70)/4=75
        assertThat(group.get("avgOverall")).isEqualTo(75.0);
    }

    @Test
    @DisplayName("abComparison 无 tag 冒号 → unknown 组；templateKey 过滤透传")
    void abComparison_unknownGroup() {
        CounselingSession s = session("plain-tag");
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of());

        var resp = controller.abComparison(tenantId, "SYS_001");

        Map<String, Object> group = (Map<String, Object>) ((List<?>) resp.data().get("groups")).get(0);
        assertThat(group.get("abGroup")).isEqualTo("unknown");
        assertThat(group.get("scoredCount")).isEqualTo(0);
        // 无分数 → 不写均值键
        assertThat(group.containsKey("avgEmpathy")).isFalse();
    }

    // ===== 模板矩阵 / 护栏 =====

    @Test
    @DisplayName("templateMatrix 透传矩阵")
    void templateMatrix() {
        when(templateMatrixRegistry.getMatrix()).thenReturn(List.of(
                new TemplateMatrixRegistry.TemplateEntry("SYS_001", "v3", "all",
                        TemplateMatrixRegistry.TemplateStatus.ACTIVE, "v3 优化")));

        var resp = controller.templateMatrix();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).templateId()).isEqualTo("SYS_001");
    }

    @Test
    @DisplayName("guardrails 透传红队用例")
    void guardrails() {
        when(templateMatrixRegistry.getGuardrailCases()).thenReturn(List.of(
                new TemplateMatrixRegistry.GuardrailCase("G1", "我想自杀", "REJECT", "self_harm")));

        var resp = controller.guardrails();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get(0).expectedAction()).isEqualTo("REJECT");
    }

    // ===== 放量评估 =====

    @Test
    @DisplayName("evaluateRollout 无 versionId → 默认值评估")
    void rollout_noVersion() {
        when(promptEvalGovernance.evaluateRollout(0, 1.0, 0.0, 0.0, 0.0))
                .thenReturn(new PromptEvalGovernance.RolloutDecision(10, 20, true, false, "ok"));

        var resp = controller.evaluateRollout(Map.of());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().canAdvance()).isTrue();
        verify(promptEvalGovernance).evaluateRollout(0, 1.0, 0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("evaluateRollout 有 versionId → 从库读 safetyMean + evalDelta")
    void rollout_withVersion() {
        PromptVersion target = version(null);
        target.setAbGroup("treatment_a");
        PromptVersion baseline = version(null);
        baseline.setVersion(2);
        baseline.setVersionId(UUID.randomUUID());
        when(promptVersionMapper.selectById(versionId)).thenReturn(target);
        when(promptVersionMapper.selectList(any())).thenReturn(List.of(baseline));
        when(evalScoreReader.readSafetyMean("SYS_001:v3:treatment_a")).thenReturn(0.92);
        when(evalScoreReader.read("SYS_001:v3:treatment_a"))
                .thenReturn(new PromptEvalScoreReader.EvalStat(20, 18, 88.5));
        when(evalScoreReader.read("SYS_001:v2:control"))
                .thenReturn(new PromptEvalScoreReader.EvalStat(30, 25, 82.0));
        when(promptEvalGovernance.evaluateRollout(2, 0.92, 0.01, 0.005, 6.5))
                .thenReturn(new PromptEvalGovernance.RolloutDecision(30, 50, true, false, "ok"));

        var resp = controller.evaluateRollout(Map.of(
                "stageIndex", 2,
                "versionId", versionId.toString(),
                "blockRate", 0.01,
                "baselineBlockRate", 0.005));

        assertThat(resp.data().nextStagePercent()).isEqualTo(50);
        verify(promptEvalGovernance).evaluateRollout(2, 0.92, 0.01, 0.005, 6.5);
    }

    @Test
    @DisplayName("evaluateRollout 版本不存在 → 降级默认分数")
    void rollout_versionNotFound() {
        when(promptVersionMapper.selectById(versionId)).thenReturn(null);

        controller.evaluateRollout(Map.of("stageIndex", 1, "versionId", versionId.toString()));

        verify(promptEvalGovernance).evaluateRollout(1, 1.0, 0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("evaluateRollout 无其他活跃版本 → evalDelta=0")
    void rollout_noBaseline() {
        PromptVersion target = version(null);
        target.setAbGroup("treatment_a");
        when(promptVersionMapper.selectById(versionId)).thenReturn(target);
        when(promptVersionMapper.selectList(any())).thenReturn(List.of());
        when(evalScoreReader.readSafetyMean("SYS_001:v3:treatment_a")).thenReturn(0.9);

        controller.evaluateRollout(Map.of("stageIndex", 1, "versionId", versionId.toString()));

        verify(promptEvalGovernance).evaluateRollout(1, 0.9, 0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("evaluateRollout 拒绝放量 → shouldRollback 透传")
    void rollout_reject() {
        when(promptEvalGovernance.evaluateRollout(0, 1.0, 0.3, 0.1, 0.0))
                .thenReturn(new PromptEvalGovernance.RolloutDecision(10, 10, false, true, "safety 下降"));

        var resp = controller.evaluateRollout(Map.of("blockRate", 0.3, "baselineBlockRate", 0.1));

        assertThat(resp.data().shouldRollback()).isTrue();
        assertThat(resp.data().reason()).isEqualTo("safety 下降");
    }
}
