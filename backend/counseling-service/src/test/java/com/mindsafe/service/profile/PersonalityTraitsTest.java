package com.mindsafe.service.profile;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private StudentProfileService studentProfileService;
    private ProfileExtractorService profileExtractorService;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        studentProfileService = new StudentProfileService(profileMapper, sessionMapper, riskEventMapper);
        profileExtractorService = new ProfileExtractorService(aiChatService, profileMapper);
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
