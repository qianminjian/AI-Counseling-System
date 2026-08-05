package com.mindsafe.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.orchestrator.ProfileSignals;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PROF-016~018 性格特征功能单元测试
 */
@ExtendWith(MockitoExtension.class)
class PersonalityTraitsTest {

    @Mock
    private StudentProfileMapper profileMapper;
    @Mock
    private CounselingSessionMapper sessionMapper;
    @Mock
    private RiskEventMapper riskEventMapper;
    @Mock
    private AiChatService aiChatService;

    private final ProfileMergeGate profileMergeGate = new ProfileMergeGate();

    private StudentProfileService studentProfileService;
    private ProfileExtractorService profileExtractorService;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        studentProfileService = new StudentProfileService(profileMapper, sessionMapper, riskEventMapper);
        profileExtractorService = new ProfileExtractorService(aiChatService, profileMapper, profileMergeGate);
    }

    @Nested
    @DisplayName("PROF-018: buildProfilePrompt 性格策略段")
    class BuildProfilePromptPersonality {

        @Test
        @DisplayName("高内向+高敏感+高好奇+兴趣 → 完整策略输出")
        void fullPersonalityStrategy() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("""
                    {"introversion":0.8,"sensitivity":0.75,"curiosity":0.9,"dominant_interests":["动物","画画","游戏"]}
                    """);
            when(profileMapper.selectOne(any())).thenReturn(profile);

            String prompt = studentProfileService.buildProfilePrompt(TENANT, USER, 3, "female");

            assertThat(prompt).contains("## 性格特征与策略");
            assertThat(prompt).contains("内向偏高：不追问、给选择题、允许沉默");
            assertThat(prompt).contains("情绪敏感：语气更轻柔");
            assertThat(prompt).contains("好奇心强：用探索/实验比喻");
            assertThat(prompt).contains("兴趣取材：该生喜欢「动物、画画、游戏」");
        }

        @Test
        @DisplayName("低内向+低敏感+低好奇 → 对应低值策略")
        void lowPersonalityStrategy() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("""
                    {"introversion":0.2,"sensitivity":0.1,"curiosity":0.2}
                    """);
            when(profileMapper.selectOne(any())).thenReturn(profile);

            String prompt = studentProfileService.buildProfilePrompt(TENANT, USER, 5, "male");

            assertThat(prompt).contains("外向活跃：可以开放提问");
            assertThat(prompt).contains("情绪稳定：可以适度挑战");
            assertThat(prompt).contains("偏好稳定：用安全/稳定比喻");
            assertThat(prompt).doesNotContain("内向偏高");
        }

        @Test
        @DisplayName("中间值(0.4-0.6) → 不输出策略（避免过度标签化）")
        void midRangeNoStrategy() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("""
                    {"introversion":0.5,"sensitivity":0.5,"curiosity":0.5}
                    """);
            when(profileMapper.selectOne(any())).thenReturn(profile);

            String prompt = studentProfileService.buildProfilePrompt(TENANT, USER, 4, null);

            assertThat(prompt).contains("## 性格特征与策略");
            assertThat(prompt).doesNotContain("内向偏高");
            assertThat(prompt).doesNotContain("外向活跃");
        }

        @Test
        @DisplayName("空 personality_traits → 不输出性格段")
        void emptyPersonalityOmitted() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("{}");
            when(profileMapper.selectOne(any())).thenReturn(profile);

            String prompt = studentProfileService.buildProfilePrompt(TENANT, USER, 4, null);

            assertThat(prompt).doesNotContain("## 性格特征与策略");
        }
    }

    @Nested
    @DisplayName("PROF-017: extractAndMerge 性格维度合并")
    class ExtractAndMergePersonality {

        @Test
        @DisplayName("首次提炼 → 直接写入数值")
        void firstExtraction() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("{}");
            when(profileMapper.selectOne(any())).thenReturn(profile);
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("""
                    {"personality_traits":{"introversion":0.8,"sensitivity":0.6,"curiosity":0.3,"dominant_interests":["动物"]}}
                    """);

            profileExtractorService.extractAndMerge(TENANT, USER, "conversation", "summary");

            verify(profileMapper).updateById(any(StudentProfile.class));
        }

        @Test
        @DisplayName("EMA 合并 → 历史值加权")
        void emaMerge() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("""
                    {"introversion":0.6,"sensitivity":0.4,"curiosity":0.5,"dominant_interests":["画画"]}
                    """);
            when(profileMapper.selectOne(any())).thenReturn(profile);
            // 新值 introversion=1.0 → EMA: 0.4*1.0 + 0.6*0.6 = 0.76
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("""
                    {"personality_traits":{"introversion":1.0,"dominant_interests":["游戏"]}}
                    """);

            profileExtractorService.extractAndMerge(TENANT, USER, "conversation", "summary");

            verify(profileMapper).updateById(any(StudentProfile.class));
        }

        @Test
        @DisplayName("LLM 返回空 personality_traits → 不修改现有值")
        void emptyPatchPreservesExisting() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("""
                    {"introversion":0.7,"dominant_interests":["运动"]}
                    """);
            when(profileMapper.selectOne(any())).thenReturn(profile);
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("""
                    {"communication_pref":{"expression_depth":0.5}}
                    """);

            profileExtractorService.extractAndMerge(TENANT, USER, "conversation", "summary");

            verify(profileMapper).updateById(any(StudentProfile.class));
        }
    }

    @Nested
    @DisplayName("PROF-025: 元数据戳与画像→编排信号")
    class ProvenanceMetadata {

        private final ObjectMapper om = new ObjectMapper();

        @Test
        @DisplayName("extractAndMerge → personality_traits._meta 写入 provenance/confidence/evidence_count")
        void mergeStampsMeta() throws Exception {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("{}");
            when(profileMapper.selectOne(any())).thenReturn(profile);
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("""
                    {"personality_traits":{"introversion":0.8,"dominant_interests":["恐龙"]}}
                    """);

            profileExtractorService.extractAndMerge(TENANT, USER, "conversation", "summary");

            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).updateById(captor.capture());
            JsonNode meta = om.readTree(captor.getValue().getPersonalityTraits()).path("_meta");
            JsonNode intro = meta.path("introversion");
            assertThat(intro.path("provenance").asText()).isEqualTo("llm_extract");
            assertThat(intro.path("evidence_count").asInt()).isEqualTo(1);
            assertThat(intro.path("confidence").asDouble()).isEqualTo(0.33);
            assertThat(intro.path("updated_at").asText()).isNotBlank();
            assertThat(intro.path("last_seen_at").asText()).isNotBlank();
            assertThat(meta.path("dominant_interests").path("evidence_count").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("重复提炼 → evidence_count 累加，confidence 收敛（2 次→ 0.5 达编排门槛）")
        void evidenceAccumulates() throws Exception {
            StudentProfile profile = baseProfile();
            // 已有 1 次提炼的元数据
            profile.setPersonalityTraits("""
                    {"introversion":0.6,"_meta":{"introversion":{"provenance":"llm_extract","evidence_count":1,"confidence":0.33}}}
                    """);
            when(profileMapper.selectOne(any())).thenReturn(profile);
            when(aiChatService.extractProfilePatch(anyString(), anyString())).thenReturn("""
                    {"personality_traits":{"introversion":0.8}}
                    """);

            profileExtractorService.extractAndMerge(TENANT, USER, "conversation", "summary");

            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).updateById(captor.capture());
            JsonNode intro = om.readTree(captor.getValue().getPersonalityTraits()).path("_meta").path("introversion");
            assertThat(intro.path("evidence_count").asInt()).isEqualTo(2);
            assertThat(intro.path("confidence").asDouble()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("getProfileSignals: 高置信 → 信号可用；无 _meta → 置信计 0 不可用")
        void profileSignalsConfidenceGate() {
            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("""
                    {"introversion":0.8,"dominant_interests":["恐龙","画画"],
                     "_meta":{"introversion":{"confidence":0.6,"evidence_count":3}}}
                    """);
            when(profileMapper.selectOne(any())).thenReturn(profile);

            ProfileSignals signals = studentProfileService.getProfileSignals(TENANT, USER);

            assertThat(signals).isNotNull();
            assertThat(signals.introversionUsable()).isTrue();
            assertThat(signals.introversion()).isEqualTo(0.8);
            // dominant_interests 无 _meta → 置信 0，门控拒用（宁可不用不可乱用）
            assertThat(signals.interestsUsable()).isFalse();
        }

        @Test
        @DisplayName("getProfileSignals: 无画像/空画像 → null 不阻塞")
        void noProfileReturnsNull() {
            when(profileMapper.selectOne(any())).thenReturn(null);
            assertThat(studentProfileService.getProfileSignals(TENANT, USER)).isNull();

            StudentProfile profile = baseProfile();
            profile.setPersonalityTraits("{}");
            when(profileMapper.selectOne(any())).thenReturn(profile);
            assertThat(studentProfileService.getProfileSignals(TENANT, USER)).isNull();
        }
    }

    // ===== Helper =====

    private StudentProfile baseProfile() {
        StudentProfile p = new StudentProfile();
        p.setProfileId(UUID.randomUUID());
        p.setTenantId(TENANT);
        p.setUserId(USER);
        p.setEmotionBaseline("{}");
        p.setCommunicationPref("{\"expression_depth\":0.5}");
        p.setResilience("{}");
        p.setRiskTrajectory("{}");
        p.setSocialGraph("{}");
        p.setGrowthTrack("{}");
        p.setPersonalityTraits("{}");
        p.setVersion(1);
        p.setTotalSessions(3);
        p.setLastUpdatedAt(Instant.now());
        p.setCreatedAt(Instant.now());
        return p;
    }
}
