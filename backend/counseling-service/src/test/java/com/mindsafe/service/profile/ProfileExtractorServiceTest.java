package com.mindsafe.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ProfileExtractorService 单测（PROF-003/PROF-022/PROF-023）。
 * <p>
 * 覆盖：LLM patch 解析（含 code fence）/ 三维度增量合并 / 置信门控接线 / 成长轨迹里程碑 / 失败静默降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("画像提炼合并服务")
class ProfileExtractorServiceTest {

    @Mock private AiChatService aiChatService;
    @Mock private StudentProfileMapper profileMapper;
    @Mock private ProfileMergeGate profileMergeGate;

    private ProfileExtractorService service;
    private final ObjectMapper om = new ObjectMapper();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProfileExtractorService(aiChatService, profileMapper, profileMergeGate);
    }

    private StudentProfile profile(String commPref, String resilience, String socialGraph,
                                   String growthTrack, String personality, Integer totalSessions) {
        StudentProfile p = new StudentProfile();
        p.setProfileId(UUID.randomUUID());
        p.setTenantId(tenantId);
        p.setUserId(userId);
        p.setCommunicationPref(commPref);
        p.setResilience(resilience);
        p.setSocialGraph(socialGraph);
        p.setGrowthTrack(growthTrack);
        p.setPersonalityTraits(personality);
        p.setTotalSessions(totalSessions);
        p.setVersion(3);
        return p;
    }

    @Nested
    @DisplayName("前置短路")
    class ShortCircuit {

        @Test
        @DisplayName("LLM 返回空 patch → 不查画像不更新")
        void blankPatch_noop() {
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("  ");

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            verify(profileMapper, never()).selectOne(any());
            verify(profileMapper, never()).updateById(any(StudentProfile.class));
        }

        @Test
        @DisplayName("画像不存在 → 跳过合并（等 P0 聚合先建画像）")
        void missingProfile_skips() {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("{\"communication_pref\":{\"preferred_style\":\"expressive\"}}");
            when(profileMapper.selectOne(any())).thenReturn(null);

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            verify(profileMapper, never()).updateById(any(StudentProfile.class));
        }

        @Test
        @DisplayName("LLM 调用异常 → 静默吞掉不影响主流程")
        void llmFailure_swallowed() {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenThrow(new RuntimeException("llm down"));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            verifyNoInteractions(profileMapper);
        }

        @Test
        @DisplayName("patch 为非法 JSON → 静默降级")
        void invalidPatchJson_swallowed() {
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("not-json");

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            verify(profileMapper, never()).updateById(any(StudentProfile.class));
        }
    }

    @Nested
    @DisplayName("维度增量合并")
    class Merge {

        private StudentProfile capturedUpdate() {
            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).updateById(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("沟通偏好：preferred_style/expression_depth 取最新值 + _meta 盖戳")
        void communicationPref_latestWinsAndMetaStamped() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("{\"communication_pref\":{\"preferred_style\":\"expressive\",\"expression_depth\":0.8}}");
            when(profileMapper.selectOne(any()))
                    .thenReturn(profile("{\"preferred_style\":\"reserved\"}", null, null, null, null, 2));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            JsonNode comm = om.readTree(capturedUpdate().getCommunicationPref());
            assertThat(comm.get("preferred_style").asText()).isEqualTo("expressive");
            assertThat(comm.get("expression_depth").asDouble()).isEqualTo(0.8);
            assertThat(comm.path("_meta").path("preferred_style").path("provenance").asText())
                    .isEqualTo("llm_extract");
        }

        @Test
        @DisplayName("韧性：coping_skills 按技能累加 uses（已有 2 次 + 本次 1 次 = 3）")
        void resilience_copingSkillsAccumulate() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("{\"resilience\":{\"coping_skills_used\":[\"breathing\"],\"self_efficacy\":0.65}}");
            when(profileMapper.selectOne(any()))
                    .thenReturn(profile(null,
                            "{\"coping_skills\":{\"breathing\":{\"uses\":2,\"effective\":true}}}",
                            null, null, null, 2));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            JsonNode res = om.readTree(capturedUpdate().getResilience());
            assertThat(res.path("coping_skills").path("breathing").path("uses").asInt()).isEqualTo(3);
            assertThat(res.get("self_efficacy").asDouble()).isEqualTo(0.65);
        }

        @Test
        @DisplayName("社交图谱：key_persons 按 role 稳定键增量更新 sentiment")
        void socialGraph_roleKeyedMerge() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("{\"social_graph\":{\"key_persons\":[{\"role\":\"妈妈\",\"sentiment\":0.6}],"
                            + "\"help_seeking\":0.9}}");
            when(profileMapper.selectOne(any()))
                    .thenReturn(profile(null, null,
                            "{\"key_persons\":{\"妈妈\":{\"role\":\"妈妈\",\"sentiment\":0.2}}}",
                            null, null, 2));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            JsonNode social = om.readTree(capturedUpdate().getSocialGraph());
            assertThat(social.path("key_persons").path("妈妈").path("sentiment").asDouble()).isEqualTo(0.6);
            assertThat(social.get("help_seeking").asDouble()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("人格特质：已有数值走置信门控（ProfileMergeGate），无值直接写入")
        void personalityTraits_gateForExisting_directForNew() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("{\"personality_traits\":{\"introversion\":0.8,\"curiosity\":0.5}}");
            when(profileMapper.selectOne(any()))
                    .thenReturn(profile(null, null, null, null, "{\"introversion\":0.3}", 2));
            when(profileMergeGate.merge(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(new ProfileMergeGate.MergeDecision(0.42, 0.7, true, "WEIGHTED_MERGE"));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            JsonNode traits = om.readTree(capturedUpdate().getPersonalityTraits());
            // introversion 已有 → 走门控结果 0.42
            assertThat(traits.get("introversion").asDouble()).isEqualTo(0.42);
            // curiosity 首次 → 直接写入
            assertThat(traits.get("curiosity").asDouble()).isEqualTo(0.5);
            verify(profileMergeGate).merge(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("code fence 包裹的 patch 可正常解析")
        void codeFenceStripped() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("```json\n{\"communication_pref\":{\"preferred_style\":\"narrative\"}}\n```");
            when(profileMapper.selectOne(any())).thenReturn(profile(null, null, null, null, null, 1));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            JsonNode comm = om.readTree(capturedUpdate().getCommunicationPref());
            assertThat(comm.get("preferred_style").asText()).isEqualTo("narrative");
        }

        @Test
        @DisplayName("版本号递增 + lastUpdatedAt 刷新")
        void versionIncremented() {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("{\"communication_pref\":{\"preferred_style\":\"expressive\"}}");
            when(profileMapper.selectOne(any())).thenReturn(profile(null, null, null, null, null, 1));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            StudentProfile update = capturedUpdate();
            assertThat(update.getVersion()).isEqualTo(4);
            assertThat(update.getLastUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("成长轨迹与里程碑")
    class GrowthTrack {

        @Test
        @DisplayName("第 1 次会话 → first_session_completed 里程碑")
        void firstSessionMilestone() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("{}");
            when(profileMapper.selectOne(any())).thenReturn(profile(null, null, null, null, null, 0));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).updateById(captor.capture());
            JsonNode growth = om.readTree(captor.getValue().getGrowthTrack());
            assertThat(growth.path("session_frequency").path("total_sessions").asInt()).isEqualTo(1);
            assertThat(growth.path("session_frequency").path("regularity").asText()).isEqualTo("irregular");
            assertThat(growth.path("milestones").toString()).contains("first_session_completed");
        }

        @Test
        @DisplayName("第 5 次会话 → consistent_attendance；使用应对技能 → first_cbt_skill_use")
        void attendanceAndSkillMilestones() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString()))
                    .thenReturn("{\"resilience\":{\"coping_skills_used\":[\"grounding\"]}}");
            when(profileMapper.selectOne(any())).thenReturn(profile(null, null, null, null, null, 4));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).updateById(captor.capture());
            JsonNode growth = om.readTree(captor.getValue().getGrowthTrack());
            assertThat(growth.path("session_frequency").path("regularity").asText()).isEqualTo("moderate");
            assertThat(growth.path("milestones").toString())
                    .contains("consistent_attendance")
                    .contains("first_cbt_skill_use");
        }

        @Test
        @DisplayName("已有里程碑不重复添加（幂等）")
        void milestoneIdempotent() throws Exception {
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("{}");
            when(profileMapper.selectOne(any())).thenReturn(profile(null, null, null,
                    "{\"milestones\":[{\"type\":\"first_session_completed\",\"period\":\"week_1\"}]}", null, 0));

            service.extractAndMerge(tenantId, userId, "对话", "摘要");

            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).updateById(captor.capture());
            JsonNode milestones = om.readTree(captor.getValue().getGrowthTrack()).path("milestones");
            long count = 0;
            for (JsonNode m : milestones) {
                if ("first_session_completed".equals(m.path("type").asText())) count++;
            }
            assertThat(count).isEqualTo(1);
        }
    }
}
