package com.mindsafe.service.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.StudentProfileMapper;
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
import static org.mockito.Mockito.when;

/**
 * ProfileRadarService 单测（PROF-004，design/23 §6）。
 * <p>
 * 覆盖：6 维度评分启发式（数值契约 + 历史字符串兼容）/ 无画像默认值 / 里程碑提取。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("画像雷达图服务")
class ProfileRadarServiceTest {

    @Mock
    private StudentProfileMapper profileMapper;

    private ProfileRadarService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProfileRadarService(profileMapper, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private int scoreOf(Map<String, Object> radar, String dimensionName) {
        List<Map<String, Object>> dims = (List<Map<String, Object>>) radar.get("dimensions");
        return dims.stream()
                .filter(d -> dimensionName.equals(d.get("name")))
                .mapToInt(d -> (Integer) d.get("score"))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("无画像 → 全维度默认 50，hasProfile=false")
    void noProfile_defaults() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> radar = service.getRadarData(tenantId, studentId);

        assertThat((Boolean) radar.get("hasProfile")).isFalse();
        assertThat((Integer) radar.get("totalSessions")).isZero();
        for (String dim : List.of("情绪稳定度", "沟通开放度", "心理韧性", "风险指数", "社交满意度", "成长动力")) {
            assertThat(scoreOf(radar, dim)).isEqualTo(50);
        }
    }

    @Test
    @DisplayName("非法 JSON 维度 → 降级默认分，不抛异常")
    void invalidJson_fallsBackToDefault() {
        StudentProfile profile = new StudentProfile();
        profile.setEmotionBaseline("not-json{{{");
        when(profileMapper.selectOne(any())).thenReturn(profile);

        Map<String, Object> radar = service.getRadarData(tenantId, studentId);

        assertThat(scoreOf(radar, "情绪稳定度")).isEqualTo(50);
        assertThat((Boolean) radar.get("hasProfile")).isTrue();
    }

    @Nested
    @DisplayName("6 维度评分（数值写入契约）")
    class DimensionScoring {

        private StudentProfile profile;

        @BeforeEach
        void init() {
            profile = new StudentProfile();
            when(profileMapper.selectOne(any())).thenReturn(profile);
        }

        @Test
        @DisplayName("情绪稳定度：positive_ratio=0.8 → 80；volatility=1.0 惩罚 30%")
        void emotionStability_ratioAndVolatilityPenalty() {
            profile.setEmotionBaseline("{\"positive_ratio\":0.8,\"volatility\":1.0}");

            // 80 * (1 - 1.0*0.3) = 56
            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "情绪稳定度")).isEqualTo(56);
        }

        @Test
        @DisplayName("情绪稳定度：从 distribution 推算积极占比（happy/calm 计入）")
        void emotionStability_fromDistribution() {
            profile.setEmotionBaseline("{\"distribution\":{\"happy\":3,\"calm\":1,\"sad\":4}}");

            // 积极 4/8 = 50
            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "情绪稳定度")).isEqualTo(50);
        }

        @Test
        @DisplayName("沟通开放度：expression_depth 数值 0.9 → 80；expressive 风格 +10")
        void openness_numericDepthAndStyleBonus() {
            profile.setCommunicationPref("{\"expression_depth\":0.9,\"preferred_style\":\"expressive\"}");

            // 35 + 0.9*50 = 80 → +10 = 90
            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "沟通开放度")).isEqualTo(90);
        }

        @Test
        @DisplayName("沟通开放度：兼容历史字符串 deep=85，reserved 风格 -10")
        void openness_legacyStringDepth() {
            profile.setCommunicationPref("{\"expression_depth\":\"deep\",\"preferred_style\":\"reserved\"}");

            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "沟通开放度")).isEqualTo(75);
        }

        @Test
        @DisplayName("心理韧性：self_efficacy 0.6 → 60；coping_skills 对象映射 uses 累计加分（封顶 +20）")
        void resilience_efficacyPlusSkills() {
            profile.setResilience("{\"self_efficacy\":0.6,\"coping_skills\":{\"breathing\":{\"uses\":12}}}");

            // 60 + min(12*2,20) = 80
            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "心理韧性")).isEqualTo(80);
        }

        @Test
        @DisplayName("风险指数（反向）：level_distribution 加权均值 → 越低风险越高分")
        void riskInverse_weightedDistribution() {
            // avgLevel = (1*5 + 2*1)/6 ≈ 1.167 → 100 - 38.9 = 61
            profile.setRiskTrajectory("{\"level_distribution\":{\"1\":5,\"2\":1}}");

            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "风险指数")).isEqualTo(61);
        }

        @Test
        @DisplayName("风险指数：无分布时按 trend（increasing → 30）")
        void riskInverse_trendFallback() {
            profile.setRiskTrajectory("{\"trend\":\"increasing\"}");

            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "风险指数")).isEqualTo(30);
        }

        @Test
        @DisplayName("社交满意度：help_seeking 数值 1.0 → 80；key_persons 对象映射情感均值调整")
        void social_numericHelpSeekingAndSentiment() {
            profile.setSocialGraph("{\"help_seeking\":1.0,\"key_persons\":{\"妈妈\":{\"role\":\"妈妈\",\"sentiment\":0.5}}}");

            // 30 + 1.0*50 = 80 → +0.5*20 = 90
            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "社交满意度")).isEqualTo(90);
        }

        @Test
        @DisplayName("社交满意度：兼容历史字符串 willing=80")
        void social_legacyStringHelpSeeking() {
            profile.setSocialGraph("{\"help_seeking\":\"willing\"}");

            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "社交满意度")).isEqualTo(80);
        }

        @Test
        @DisplayName("成长动力：2 个里程碑 → 70；regularity=regular +10 → 80")
        void growth_milestonesAndRegularity() {
            profile.setGrowthTrack("{\"milestones\":[{\"type\":\"first_voluntary_share\"},{\"type\":\"first_cbt_skill_use\"}],"
                    + "\"session_frequency\":{\"regularity\":\"regular\"}}");

            // 40 + 2*15 = 70 → +10 = 80
            assertThat(scoreOf(service.getRadarData(tenantId, studentId), "成长动力")).isEqualTo(80);
        }
    }

    @Nested
    @DisplayName("里程碑提取")
    class Milestones {

        @Test
        @DisplayName("里程碑带中文标签 + period；未知 type 原样输出")
        @SuppressWarnings("unchecked")
        void milestoneLabels() {
            StudentProfile profile = new StudentProfile();
            profile.setGrowthTrack("{\"milestones\":["
                    + "{\"type\":\"first_voluntary_share\",\"period\":\"week_2\"},"
                    + "{\"type\":\"custom_type\"}]}");
            when(profileMapper.selectOne(any())).thenReturn(profile);

            Map<String, Object> radar = service.getRadarData(tenantId, studentId);
            List<Map<String, String>> milestones = (List<Map<String, String>>) radar.get("milestones");

            assertThat(milestones).hasSize(2);
            assertThat(milestones.get(0).get("label")).isEqualTo("首次主动分享");
            assertThat(milestones.get(0).get("period")).isEqualTo("week_2");
            assertThat(milestones.get(1).get("label")).isEqualTo("custom_type");
        }

        @Test
        @DisplayName("无成长轨迹 → 空里程碑列表")
        @SuppressWarnings("unchecked")
        void noGrowthTrack_emptyMilestones() {
            StudentProfile profile = new StudentProfile();
            when(profileMapper.selectOne(any())).thenReturn(profile);

            Map<String, Object> radar = service.getRadarData(tenantId, studentId);

            assertThat((List<Map<String, String>>) radar.get("milestones")).isEmpty();
        }
    }
}
