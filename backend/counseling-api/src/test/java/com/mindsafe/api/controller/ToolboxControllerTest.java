package com.mindsafe.api.controller;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.toolbox.MoodCheckRecorder;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolboxController 单元测试（P1 覆盖率冲刺：工具箱列表/分类/SOS/心情记录）
 */
class ToolboxControllerTest {

    private ToolboxRegistry toolboxRegistry;
    private MoodCheckRecorder moodCheckRecorder;
    private UserMapper userMapper;
    private ToolboxController controller;

    private final UUID studentUserId = UUID.randomUUID();

    private static final ToolDefinition TOOL = new ToolDefinition(
            "breathing_box", "深呼吸", "🫧", 150, 1, true, true, "breathing_star", ToolCategory.BREATHING);

    @BeforeEach
    void setUp() {
        toolboxRegistry = mock(ToolboxRegistry.class);
        moodCheckRecorder = mock(MoodCheckRecorder.class);
        userMapper = mock(UserMapper.class);
        controller = new ToolboxController(toolboxRegistry, moodCheckRecorder, userMapper);
    }

    private Authentication studentAuth(String gradeCode) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(studentUserId);
        User u = new User();
        u.setUserId(studentUserId);
        u.setGradeCode(gradeCode);
        when(userMapper.selectById(studentUserId)).thenReturn(u);
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
        verify(userMapper, never()).selectById(anyUuid());
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
        when(userMapper.selectById(studentUserId)).thenReturn(null);
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
    @DisplayName("recordMoodCheck 缺参数 → 400")
    void recordMoodCheck_missingParam() {
        ApiResponse<Map<String, Object>> resp = controller.recordMoodCheck(
                Map.of("toolId", "breathing_box", "preMood", 3), studentAuth("G3"));

        assertThat(resp.code()).isEqualTo(400);
        assertThat(resp.message()).contains("缺少必要参数");
        verify(moodCheckRecorder, never()).record(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("recordMoodCheck 未知工具 → 400")
    void recordMoodCheck_unknownTool() {
        when(toolboxRegistry.getById("ghost_tool")).thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.recordMoodCheck(
                Map.of("toolId", "ghost_tool", "preMood", 3, "postMood", 4), studentAuth("G3"));

        assertThat(resp.code()).isEqualTo(400);
        assertThat(resp.message()).contains("未知工具");
    }

    @Test
    @DisplayName("recordMoodCheck 成功（改善）→ 效果数据返回 + needsAttention=false")
    void recordMoodCheck_improved() {
        when(toolboxRegistry.getById("breathing_box")).thenReturn(Optional.of(TOOL));
        MoodCheckRecorder.MoodEffect effect =
                new MoodCheckRecorder.MoodEffect("breathing_box", 3, 5, 2, MoodCheckRecorder.EffectLevel.IMPROVED);
        when(moodCheckRecorder.record("breathing_box", 3, 5)).thenReturn(effect);
        when(moodCheckRecorder.needsAttention(effect)).thenReturn(false);

        ApiResponse<Map<String, Object>> resp = controller.recordMoodCheck(
                Map.of("toolId", "breathing_box", "preMood", 3, "postMood", 5), studentAuth("G3"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("delta")).isEqualTo(2);
        assertThat(resp.data().get("level")).isEqualTo("IMPROVED");
        assertThat(resp.data().get("needsAttention")).isEqualTo(false);
        verify(moodCheckRecorder).record("breathing_box", 3, 5);
    }

    @Test
    @DisplayName("recordMoodCheck 恶化 → needsAttention=true")
    void recordMoodCheck_worsened() {
        when(toolboxRegistry.getById("breathing_box")).thenReturn(Optional.of(TOOL));
        MoodCheckRecorder.MoodEffect effect =
                new MoodCheckRecorder.MoodEffect("breathing_box", 5, 2, -3, MoodCheckRecorder.EffectLevel.WORSENED);
        when(moodCheckRecorder.record("breathing_box", 5, 2)).thenReturn(effect);
        when(moodCheckRecorder.needsAttention(effect)).thenReturn(true);

        ApiResponse<Map<String, Object>> resp = controller.recordMoodCheck(
                Map.of("toolId", "breathing_box", "preMood", 5, "postMood", 2), studentAuth("G3"));

        assertThat(resp.data().get("needsAttention")).isEqualTo(true);
        assertThat(resp.data().get("level")).isEqualTo("WORSENED");
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
}
