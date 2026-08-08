package com.mindsafe.service.toolbox;

import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.relaxation.RelaxationService;
import com.mindsafe.service.toolbox.MoodCheckRecorder.EffectLevel;
import com.mindsafe.service.toolbox.MoodCheckRecorder.MoodEffect;
import com.mindsafe.service.toolbox.MoodCheckService.MoodCheckResult;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolCategory;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MoodCheckService 编排单元测试（BA-14 下沉后可测）
 * <p>
 * 覆盖：参数校验 → 工具验证 → 效果计算 → 落库 → 恶化告警的完整编排链
 * （此前全编排在 Controller，仅纯函数测试，本链零覆盖）。
 */
class MoodCheckServiceTest {

    private ToolboxRegistry toolboxRegistry;
    private MoodCheckRecorder moodCheckRecorder;
    private RelaxationService relaxationService;
    private MoodCheckService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private static final ToolDefinition TOOL = new ToolDefinition(
            "breathing_box", "深呼吸", "🫧", 150, 1, true, true, "breathing_star", ToolCategory.BREATHING);

    @BeforeEach
    void setUp() {
        toolboxRegistry = mock(ToolboxRegistry.class);
        moodCheckRecorder = mock(MoodCheckRecorder.class);
        relaxationService = mock(RelaxationService.class);
        service = new MoodCheckService(toolboxRegistry, moodCheckRecorder, relaxationService);
    }

    @Test
    @DisplayName("缺参数 → BizException，不触达 recorder/落库")
    void missingParam() {
        assertThatThrownBy(() -> service.record(tenantId, userId, null, 3, 4))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少必要参数");
        assertThatThrownBy(() -> service.record(tenantId, userId, "breathing_box", null, 4))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.record(tenantId, userId, "breathing_box", 3, null))
                .isInstanceOf(BizException.class);

        verify(moodCheckRecorder, never()).record(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(relaxationService, never()).recordSession(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("未知工具 → BizException")
    void unknownTool() {
        when(toolboxRegistry.getById("ghost_tool")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(tenantId, userId, "ghost_tool", 3, 4))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知工具");
        verify(relaxationService, never()).recordSession(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("成功（改善）→ 效果 + needsAttention=false + 落库（时长取工具定义真实值）")
    void improved() {
        when(toolboxRegistry.getById("breathing_box")).thenReturn(Optional.of(TOOL));
        MoodEffect effect = new MoodEffect("breathing_box", 3, 5, 2, EffectLevel.IMPROVED);
        when(moodCheckRecorder.record("breathing_box", 3, 5)).thenReturn(effect);
        when(moodCheckRecorder.needsAttention(effect)).thenReturn(false);

        MoodCheckResult result = service.record(tenantId, userId, "breathing_box", 3, 5);

        assertThat(result.needsAttention()).isFalse();
        assertThat(result.effect()).isEqualTo(effect);
        verify(moodCheckRecorder).record("breathing_box", 3, 5);
        verify(relaxationService).recordSession(tenantId, userId, "breathing_box", 150, true);
    }

    @Test
    @DisplayName("恶化 → needsAttention=true + 仍落库")
    void worsened() {
        when(toolboxRegistry.getById("breathing_box")).thenReturn(Optional.of(TOOL));
        MoodEffect effect = new MoodEffect("breathing_box", 5, 2, -3, EffectLevel.WORSENED);
        when(moodCheckRecorder.record("breathing_box", 5, 2)).thenReturn(effect);
        when(moodCheckRecorder.needsAttention(effect)).thenReturn(true);

        MoodCheckResult result = service.record(tenantId, userId, "breathing_box", 5, 2);

        assertThat(result.needsAttention()).isTrue();
        verify(relaxationService).recordSession(tenantId, userId, "breathing_box", 150, true);
    }

    @Test
    @DisplayName("情绪分越界 → recorder 判 INVALID 并照常落库（产品语义：容忍非法输入）")
    void invalidMood() {
        when(toolboxRegistry.getById("breathing_box")).thenReturn(Optional.of(TOOL));
        MoodEffect effect = new MoodEffect("breathing_box", 9, 2, 0, EffectLevel.INVALID);
        when(moodCheckRecorder.record("breathing_box", 9, 2)).thenReturn(effect);
        when(moodCheckRecorder.needsAttention(effect)).thenReturn(false);

        MoodCheckResult result = service.record(tenantId, userId, "breathing_box", 9, 2);

        assertThat(result.effect().level()).isEqualTo(EffectLevel.INVALID);
        verify(relaxationService).recordSession(tenantId, userId, "breathing_box", 150, true);
    }
}
