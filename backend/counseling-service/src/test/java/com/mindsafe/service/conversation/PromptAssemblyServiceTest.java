package com.mindsafe.service.conversation;

import com.mindsafe.ai.cbt.CbtStageRouter;
import com.mindsafe.service.prompt.PromptVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PromptAssemblyService 纯组装测试（ARCH-001 C1 抽取）。
 * <p>
 * 行为基线：从 ConversationServiceImpl 主链路/暖场链路原样迁移
 * （SYS_001 → LANG_xxx → GENDER_STYLE → EMO_001 → CBT 指令 → RAG 固定顺序，空段省略），
 * 语义不调整只收敛位置 + 消除两处 gradeLevel/langKey 重复计算（DRY）。
 * B4：性别风格段（GENDER_STYLE_{MALE|FEMALE|NEUTRAL}_{LOW|MID|HIGH}）随 gender 参数组装。
 */
@ExtendWith(MockitoExtension.class)
class PromptAssemblyServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID STUDENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock
    private PromptVersionService promptVersionService;
    @Mock
    private CbtStageRouter cbtStageRouter;

    private PromptAssemblyService service;

    @BeforeEach
    void setUp() {
        service = new PromptAssemblyService(promptVersionService, cbtStageRouter);
    }

    private PromptVersionService.ResolvedPrompt resolved(String content) {
        return new PromptVersionService.ResolvedPrompt(content, "v1", "A");
    }

    @Nested
    @DisplayName("主链路组装 assembleMainPrompt")
    class MainPromptTests {

        @Test
        @DisplayName("SYS_001 → LANG_001 → GENDER_STYLE → EMO_001 → CBT 指令 → RAG 全段拼接（1-2 年级·男生）")
        void assembleMain_fullChain() {
            when(promptVersionService.resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【系统】"));
            when(promptVersionService.resolveRaw(eq(TENANT_ID), eq("LANG_001"), eq(STUDENT_ID)))
                    .thenReturn(resolved("【语言】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("GENDER_STYLE_MALE_LOW"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【风格】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("EMO_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【情绪】"));
            when(cbtStageRouter.stageDirective(any())).thenReturn("【CBT指令】");

            PromptAssemblyService.AssembledPrompt prompt = service.assembleMainPrompt(TENANT_ID, STUDENT_ID, 2, "开心",
                    Map.of("strategy", "s1"), mockStageMark(), "【RAG】", "male");

            assertThat(prompt.content()).isEqualTo("【系统】\n\n【语言】\n\n【风格】\n\n【情绪】\n\n【CBT指令】\n\n【RAG】");
            assertThat(prompt.versionTag()).isEqualTo("v1");
            verify(promptVersionService).resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID), any());
            verify(promptVersionService).resolveRaw(eq(TENANT_ID), eq("LANG_001"), eq(STUDENT_ID));
            verify(promptVersionService).resolve(eq(TENANT_ID), eq("GENDER_STYLE_MALE_LOW"), eq(STUDENT_ID), any());
        }

        @Test
        @DisplayName("RAG 为空 → 不追加 RAG 段，其余段照常（3 年级·女生）")
        void assembleMain_emptyRag() {
            when(promptVersionService.resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【系统】"));
            when(promptVersionService.resolveRaw(eq(TENANT_ID), eq("LANG_002"), eq(STUDENT_ID)))
                    .thenReturn(resolved("【语言】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("GENDER_STYLE_FEMALE_MID"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【风格】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("EMO_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【情绪】"));
            when(cbtStageRouter.stageDirective(any())).thenReturn("【CBT指令】");

            PromptAssemblyService.AssembledPrompt prompt = service.assembleMainPrompt(TENANT_ID, STUDENT_ID, 3, null,
                    Map.of(), mockStageMark(), "", "female");

            assertThat(prompt.content()).isEqualTo("【系统】\n\n【语言】\n\n【风格】\n\n【情绪】\n\n【CBT指令】");
        }

        @Test
        @DisplayName("SYS_001 变量：grade_level 按年级分层、emotion_tag 为 null 时注入空串")
        void assembleMain_sysVariables() {
            when(promptVersionService.resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【系统】"));
            when(promptVersionService.resolveRaw(eq(TENANT_ID), eq("LANG_002"), eq(STUDENT_ID)))
                    .thenReturn(resolved("【语言】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("GENDER_STYLE_MALE_MID"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【风格】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("EMO_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【情绪】"));
            when(cbtStageRouter.stageDirective(any())).thenReturn("【CBT指令】");

            service.assembleMainPrompt(TENANT_ID, STUDENT_ID, 4, null, Map.of(), mockStageMark(), "", "male");

            verify(promptVersionService).resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID),
                    org.mockito.ArgumentMatchers.argThat(vars ->
                            "3-4".equals(vars.get("grade_level"))
                                    && "".equals(vars.get("emotion_tag"))
                                    && "normal_counseling".equals(vars.get("session_mode"))));
            verify(promptVersionService, never()).resolveRaw(eq(TENANT_ID), eq("LANG_001"), eq(STUDENT_ID));
            verify(promptVersionService, never()).resolveRaw(eq(TENANT_ID), eq("LANG_003"), eq(STUDENT_ID));
        }

        @Test
        @DisplayName("年级分层边界：5-6 年级 → LANG_003 + grade_level=5-6 + 未指定性别归 NEUTRAL")
        void assembleMain_grade56() {
            when(promptVersionService.resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【系统】"));
            when(promptVersionService.resolveRaw(eq(TENANT_ID), eq("LANG_003"), eq(STUDENT_ID)))
                    .thenReturn(resolved("【语言】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("GENDER_STYLE_NEUTRAL_HIGH"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【风格】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("EMO_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【情绪】"));
            when(cbtStageRouter.stageDirective(any())).thenReturn("【CBT指令】");

            service.assembleMainPrompt(TENANT_ID, STUDENT_ID, 6, "开心", Map.of(), mockStageMark(), "", null);

            verify(promptVersionService).resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID),
                    org.mockito.ArgumentMatchers.argThat(vars -> "5-6".equals(vars.get("grade_level"))));
        }
    }

    @Nested
    @DisplayName("暖场组装 assembleNudgePrompt")
    class NudgePromptTests {

        @Test
        @DisplayName("SYS_001 → LANG_001 → GENDER_STYLE → TSK_004 四段拼接（1-2 年级·男生）")
        void assembleNudge_order() {
            when(promptVersionService.resolve(eq(TENANT_ID), eq("SYS_001"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【系统】"));
            when(promptVersionService.resolveRaw(eq(TENANT_ID), eq("LANG_001"), eq(STUDENT_ID)))
                    .thenReturn(resolved("【语言】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("GENDER_STYLE_MALE_LOW"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【风格】"));
            when(promptVersionService.resolve(eq(TENANT_ID), eq("TSK_004"), eq(STUDENT_ID), any()))
                    .thenReturn(resolved("【暖场】"));

            String prompt = service.assembleNudgePrompt(TENANT_ID, STUDENT_ID, 2, "平静",
                    Map.of("silence_seconds", "30", "warmth_level", "1", "direction", "问候"), "male");

            assertThat(prompt).isEqualTo("【系统】\n\n【语言】\n\n【风格】\n\n【暖场】");
            // 暖场链路不触碰 EMO_001 与 CBT 指令
            verify(promptVersionService, never()).resolve(eq(TENANT_ID), eq("EMO_001"), eq(STUDENT_ID), any());
            verify(cbtStageRouter, never()).stageDirective(any());
            // TSK_004 模板变量原样透传
            verify(promptVersionService).resolve(eq(TENANT_ID), eq("TSK_004"), eq(STUDENT_ID),
                    org.mockito.ArgumentMatchers.argThat(vars ->
                            "30".equals(vars.get("silence_seconds"))
                                    && "1".equals(vars.get("warmth_level"))
                                    && "问候".equals(vars.get("direction"))));
        }
    }

    private CbtStageRouter.StageMark mockStageMark() {
        return new CbtStageRouter.StageMark(CbtStageRouter.CbtStage.RAPPORT,
                CbtStageRouter.AgeStrategy.BALANCED, List.of("ACTIVE_LISTENING"), true);
    }

    @Nested
    @DisplayName("genderStyleKeyOf 键生成")
    class GenderStyleKeyTests {

        @Test
        @DisplayName("3 性别 × 3 年级段 = 9 键全覆盖")
        void allNineCombinations() {
            assertThat(PromptAssemblyService.genderStyleKeyOf("male", 1)).isEqualTo("GENDER_STYLE_MALE_LOW");
            assertThat(PromptAssemblyService.genderStyleKeyOf("male", 3)).isEqualTo("GENDER_STYLE_MALE_MID");
            assertThat(PromptAssemblyService.genderStyleKeyOf("male", 5)).isEqualTo("GENDER_STYLE_MALE_HIGH");
            assertThat(PromptAssemblyService.genderStyleKeyOf("female", 1)).isEqualTo("GENDER_STYLE_FEMALE_LOW");
            assertThat(PromptAssemblyService.genderStyleKeyOf("female", 3)).isEqualTo("GENDER_STYLE_FEMALE_MID");
            assertThat(PromptAssemblyService.genderStyleKeyOf("female", 5)).isEqualTo("GENDER_STYLE_FEMALE_HIGH");
            assertThat(PromptAssemblyService.genderStyleKeyOf(null, 1)).isEqualTo("GENDER_STYLE_NEUTRAL_LOW");
            assertThat(PromptAssemblyService.genderStyleKeyOf(null, 3)).isEqualTo("GENDER_STYLE_NEUTRAL_MID");
            assertThat(PromptAssemblyService.genderStyleKeyOf(null, 5)).isEqualTo("GENDER_STYLE_NEUTRAL_HIGH");
        }

        @Test
        @DisplayName("边界：2/4 年级归入低/中段，未知性别归 NEUTRAL")
        void boundaries() {
            assertThat(PromptAssemblyService.genderStyleKeyOf("male", 2)).isEqualTo("GENDER_STYLE_MALE_LOW");
            assertThat(PromptAssemblyService.genderStyleKeyOf("female", 4)).isEqualTo("GENDER_STYLE_FEMALE_MID");
            assertThat(PromptAssemblyService.genderStyleKeyOf("unknown", 3)).isEqualTo("GENDER_STYLE_NEUTRAL_MID");
            assertThat(PromptAssemblyService.genderStyleKeyOf("FEMALE", 6)).isEqualTo("GENDER_STYLE_NEUTRAL_HIGH");
        }
    }
}
