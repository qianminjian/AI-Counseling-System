package com.mindsafe.api.controller;

import com.mindsafe.api.dto.toolbox.MoodCheckRequest;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.AuthUserService;
import com.mindsafe.service.toolbox.MoodCheckRecorder;
import com.mindsafe.service.toolbox.MoodCheckService;
import com.mindsafe.service.toolbox.ToolboxRegistry;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolCategory;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolboxController 单元测试（P1 覆盖率冲刺：工具箱列表/分类/SOS/心情记录）
 * <p>
 * BA-03（DOC-074）：recordMoodCheck 落库练习完成（relaxation_sessions，徽章数据源）；
 * BA-07：年级解析用户查询收敛 AuthUserService。
 * BA-14：recordMoodCheck 编排下沉 MoodCheckService，Controller 薄壳（请求体 DTO + 错误抛 BizException）。
 */
class ToolboxControllerTest {

    private ToolboxRegistry toolboxRegistry;
    private MoodCheckService moodCheckService;
    private AuthUserService authUserService;
    private ToolboxController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    private static final ToolDefinition TOOL = new ToolDefinition(
            "breathing_box", "深呼吸", "🫧", 150, 1, true, true, "breathing_star", ToolCategory.BREATHING);

    @BeforeEach
    void setUp() {
        toolboxRegistry = mock(ToolboxRegistry.class);
        moodCheckService = mock(MoodCheckService.class);
        authUserService = mock(AuthUserService.class);
        controller = new ToolboxController(toolboxRegistry, moodCheckService, authUserService);
    }

    private Authentication studentAuth(String gradeCode) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(studentUserId);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, studentUserId, "student"));
        User u = new User();
        u.setUserId(studentUserId);
        u.setGradeCode(gradeCode);
        when(authUserService.findById(studentUserId)).thenReturn(u);
        return auth;
    }

    @Test
    @DisplayName("listTools 按年级过滤（G3 → listForGrade(3)）")
    void listTools_withGrade() {
        when(toolboxRegistry.listForGrade(3)).thenReturn(List.of(TOOL));

        ApiResponse<List<ToolDefinition>> resp = controller.listTools(studentAuth("G3"));

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).toolId()).isEqualTo("breathing_box");
        verify(toolboxRegistry).listForGrade(3);
    }

    @Test
    @DisplayName("listTools auth=null → 默认年级 4")
    void listTools_nullAuth() {
        when(toolboxRegistry.listForGrade(4)).thenReturn(List.of());

        ApiResponse<List<ToolDefinition>> resp = controller.listTools(null);

        assertThat(resp.data()).isEmpty();
        verify(toolboxRegistry).listForGrade(4);
        verify(authUserService, never()).findById(anyUuid());
    }

    @Test
    @DisplayName("listTools principal 非 UUID → 默认年级 4")
    void listTools_principalNotUuid() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn("not-a-uuid");
        when(toolboxRegistry.listForGrade(4)).thenReturn(List.of());

        controller.listTools(auth);

        verify(toolboxRegistry).listForGrade(4);
    }

    @Test
    @DisplayName("listTools 用户不存在 → 默认年级 4")
    void listTools_userNotFound() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(studentUserId);
        when(authUserService.findById(studentUserId)).thenReturn(null);
        when(toolboxRegistry.listForGrade(4)).thenReturn(List.of());

        controller.listTools(auth);

        verify(toolboxRegistry).listForGrade(4);
    }

    @Test
    @DisplayName("listTools gradeCode 为空 → 默认年级 4")
    void listTools_blankGradeCode() {
        when(toolboxRegistry.listForGrade(4)).thenReturn(List.of());

        controller.listTools(studentAuth(""));

        verify(toolboxRegistry).listForGrade(4);
    }

    @Test
    @DisplayName("sosTools 透传 SOS 工具列表")
    void sosTools() {
        when(toolboxRegistry.listSosTools()).thenReturn(List.of(TOOL));

        ApiResponse<List<ToolDefinition>> resp = controller.sosTools();

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).category()).isEqualTo(ToolCategory.BREATHING);
    }

    @Test
    @DisplayName("byCategory 有效分类（忽略大小写）")
    void byCategory_valid() {
        when(toolboxRegistry.listByCategory(ToolCategory.BREATHING)).thenReturn(List.of(TOOL));

        ApiResponse<List<ToolDefinition>> resp = controller.byCategory("breathing");

        assertThat(resp.data()).hasSize(1);
        verify(toolboxRegistry).listByCategory(ToolCategory.BREATHING);
    }

    @Test
    @DisplayName("byCategory 无效分类 → 400")
    void byCategory_invalid() {
        ApiResponse<List<ToolDefinition>> resp = controller.byCategory("magic");

        assertThat(resp.code()).isEqualTo(400);
        assertThat(resp.message()).contains("无效的工具分类");
    }

    @Test
    @DisplayName("recordMoodCheck 无认证 → UNAUTHORIZED（校验先于编排）")
    void recordMoodCheck_unauthorized() {
        assertThatThrownBy(() -> controller.recordMoodCheck(
                new MoodCheckRequest("breathing_box", 3, 4), null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("recordMoodCheck 编排失败（缺参数/未知工具）→ 透传 BizException")
    void recordMoodCheck_serviceRejects() {
        when(moodCheckService.record(tenantId, studentUserId, "ghost_tool", 3, 4))
                .thenThrow(new BizException(com.mindsafe.common.dto.ErrorCode.PARAM_INVALID, "未知工具: ghost_tool"));

        assertThatThrownBy(() -> controller.recordMoodCheck(
                new MoodCheckRequest("ghost_tool", 3, 4), studentAuth("G3")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知工具");
    }

    @Test
    @DisplayName("recordMoodCheck 成功（改善）→ 效果数据返回（编排透传）")
    void recordMoodCheck_improved() {
        MoodCheckRecorder.MoodEffect effect =
                new MoodCheckRecorder.MoodEffect("breathing_box", 3, 5, 2, MoodCheckRecorder.EffectLevel.IMPROVED);
        when(moodCheckService.record(tenantId, studentUserId, "breathing_box", 3, 5))
                .thenReturn(new MoodCheckService.MoodCheckResult(effect, false));

        ApiResponse<Map<String, Object>> resp = controller.recordMoodCheck(
                new MoodCheckRequest("breathing_box", 3, 5), studentAuth("G3"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("toolId")).isEqualTo("breathing_box");
        assertThat(resp.data().get("delta")).isEqualTo(2);
        assertThat(resp.data().get("level")).isEqualTo("IMPROVED");
        assertThat(resp.data().get("needsAttention")).isEqualTo(false);
        verify(moodCheckService).record(tenantId, studentUserId, "breathing_box", 3, 5);
    }

    @Test
    @DisplayName("recordMoodCheck 恶化 → needsAttention=true 透传")
    void recordMoodCheck_worsened() {
        MoodCheckRecorder.MoodEffect effect =
                new MoodCheckRecorder.MoodEffect("breathing_box", 5, 2, -3, MoodCheckRecorder.EffectLevel.WORSENED);
        when(moodCheckService.record(tenantId, studentUserId, "breathing_box", 5, 2))
                .thenReturn(new MoodCheckService.MoodCheckResult(effect, true));

        ApiResponse<Map<String, Object>> resp = controller.recordMoodCheck(
                new MoodCheckRequest("breathing_box", 5, 2), studentAuth("G3"));

        assertThat(resp.data().get("needsAttention")).isEqualTo(true);
        assertThat(resp.data().get("level")).isEqualTo("WORSENED");
        assertThat(resp.data().get("preMood")).isEqualTo(5);
    }

    private static UUID anyUuid() {
        return null;
    }

    private static String anyString() {
        return null;
    }

    private static int anyInt() {
        return 0;
    }

    private static boolean anyBoolean() {
        return false;
    }
}
