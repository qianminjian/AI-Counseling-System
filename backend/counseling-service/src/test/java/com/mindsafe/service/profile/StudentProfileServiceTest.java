package com.mindsafe.service.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StudentProfileService 单元测试（VCL-001：语音情绪聚合回注 emotionBaseline.voice）
 * <p>
 * 覆盖：voice 子对象生成（provenance=voice_ser）、跨会话 counts 累计、
 * 纯文本会话保留既有 voice 节点、无语音数据不产生 voice 键。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentProfileServiceTest {

    @Mock
    private StudentProfileMapper profileMapper;

    @Mock
    private CounselingSessionMapper sessionMapper;

    @Mock
    private RiskEventMapper riskEventMapper;

    private StudentProfileService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StudentProfileService(profileMapper, sessionMapper, riskEventMapper, objectMapper);
        when(sessionMapper.selectList(any())).thenReturn(List.of(sessionWithEmotion("sad")));
        when(riskEventMapper.selectList(any())).thenReturn(List.of());
    }

    private CounselingSession sessionWithEmotion(String emotionTag) {
        CounselingSession session = new CounselingSession();
        session.setEmotionTag(emotionTag);
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBaseline(String json) throws Exception {
        return objectMapper.readValue(json, Map.class);
    }

    @Test
    @DisplayName("首次画像 + 语音情绪 → voice 子对象含 counts/dominant/provenance=voice_ser")
    void firstProfile_voiceEmotions_createVoiceNode() throws Exception {
        when(profileMapper.selectOne(any())).thenReturn(null);

        service.updateProfile(tenantId, userId, List.of("sad", "sad", "calm"));

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileMapper).insert(captor.capture());
        Map<String, Object> baseline = parseBaseline(captor.getValue().getEmotionBaseline());
        Map<String, Object> voice = (Map<String, Object>) baseline.get("voice");
        assertThat(voice).isNotNull();
        assertThat((Map<String, Object>) voice.get("counts"))
                .containsEntry("sad", 2).containsEntry("calm", 1);
        assertThat(voice.get("dominant_emotion")).isEqualTo("sad");
        Map<String, Object> meta = (Map<String, Object>) voice.get("_meta");
        assertThat(meta.get("provenance")).isEqualTo("voice_ser");
        assertThat(meta.get("evidence_count")).isEqualTo(3);
    }

    @Test
    @DisplayName("P2-2：情绪标签含引号/反斜杠时序列化 JSON 必须合法（手写 toJson 曾产出非法 JSON）")
    void voiceLabel_withQuotes_producesValidJson() throws Exception {
        when(profileMapper.selectOne(any())).thenReturn(null);

        service.updateProfile(tenantId, userId, List.of("sad\"x", "calm\\y"));

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileMapper).insert(captor.capture());
        // 手写 toJson 直接拼接 → 含引号即非法 JSON，readValue 抛异常（红）；注入 ObjectMapper 后转义正确（绿）
        Map<String, Object> baseline = parseBaseline(captor.getValue().getEmotionBaseline());
        Map<String, Object> voice = (Map<String, Object>) baseline.get("voice");
        assertThat((Map<String, Object>) voice.get("counts"))
                .containsEntry("sad\"x", 1).containsEntry("calm\\y", 1);
    }

    @Test
    @DisplayName("既有 voice counts 跨会话累计（旧 sad:2 + 新 sad,calm → sad:3, calm:1）")
    void existingVoiceCounts_accumulate() throws Exception {
        StudentProfile existing = existingProfile(
                "{\"distribution\":{\"sad\":1.0},\"voice\":{\"counts\":{\"sad\":2}}}");
        when(profileMapper.selectOne(any())).thenReturn(existing);

        service.updateProfile(tenantId, userId, List.of("sad", "calm"));

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileMapper).updateById(captor.capture());
        Map<String, Object> baseline = parseBaseline(captor.getValue().getEmotionBaseline());
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>)
                ((Map<String, Object>) baseline.get("voice")).get("counts");
        assertThat(counts).containsEntry("sad", 3).containsEntry("calm", 1);
    }

    @Test
    @DisplayName("纯文本会话（空列表）→ 原样保留既有 voice 节点，不被冲掉")
    void textOnlySession_preservesExistingVoice() throws Exception {
        StudentProfile existing = existingProfile(
                "{\"distribution\":{\"sad\":1.0},\"voice\":{\"counts\":{\"angry\":4}}}");
        when(profileMapper.selectOne(any())).thenReturn(existing);

        service.updateProfile(tenantId, userId);

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileMapper).updateById(captor.capture());
        Map<String, Object> baseline = parseBaseline(captor.getValue().getEmotionBaseline());
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>)
                ((Map<String, Object>) baseline.get("voice")).get("counts");
        assertThat(counts).containsEntry("angry", 4);
    }

    @Test
    @DisplayName("无语音数据且无既有 voice → emotionBaseline 不产生 voice 键")
    void noVoiceDataAtAll_noVoiceNode() throws Exception {
        when(profileMapper.selectOne(any())).thenReturn(null);

        service.updateProfile(tenantId, userId);

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileMapper).insert(captor.capture());
        Map<String, Object> baseline = parseBaseline(captor.getValue().getEmotionBaseline());
        assertThat(baseline).doesNotContainKey("voice");
    }

    private StudentProfile existingProfile(String emotionBaselineJson) {
        StudentProfile profile = new StudentProfile();
        profile.setProfileId(UUID.randomUUID());
        profile.setTenantId(tenantId);
        profile.setUserId(userId);
        profile.setEmotionBaseline(emotionBaselineJson);
        profile.setVersion(1);
        return profile;
    }
}
