package com.mindsafe.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import com.mindsafe.service.profile.MemoryProfileBackfillService.MemoryEvent;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryProfileBackfillService 单元测试（MEM-101，design/50 §5.1）
 * <p>
 * 覆盖：milestone→growthTrack、person→socialGraph、provenance=memory 元数据、
 * 去重、other 类型不回注、无画像静默跳过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryProfileBackfillServiceTest {

    @Mock
    private StudentProfileMapper profileMapper;

    private MemoryProfileBackfillService service;
    private final ObjectMapper om = new ObjectMapper();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MemoryProfileBackfillService(profileMapper, om);
    }

    private StudentProfile baseProfile(String growthTrack, String socialGraph) {
        StudentProfile p = new StudentProfile();
        p.setProfileId(UUID.randomUUID());
        p.setTenantId(tenantId);
        p.setUserId(userId);
        p.setGrowthTrack(growthTrack);
        p.setSocialGraph(socialGraph);
        p.setVersion(3);
        return p;
    }

    @Test
    @DisplayName("milestone 事件回注 growthTrack.milestones，带 provenance=memory 元数据")
    void milestoneBackfillsGrowthTrack() throws Exception {
        when(profileMapper.selectOne(any())).thenReturn(baseProfile("{}", "{}"));

        service.backfill(tenantId, userId, List.of(
                new MemoryEvent("milestone", "第一次主动分享了学校的烦恼", null, "委屈")));

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileMapper).updateById(captor.capture());
        JsonNode growth = om.readTree(captor.getValue().getGrowthTrack());
        JsonNode milestone = growth.get("milestones").get(0);
        assertThat(milestone.get("type").asText()).isEqualTo("memory_key_event");
        assertThat(milestone.get("content").asText()).isEqualTo("第一次主动分享了学校的烦恼");
        assertThat(milestone.get("emotion_context").asText()).isEqualTo("委屈");
        JsonNode meta = growth.path("_meta").path("milestones");
        assertThat(meta.get("provenance").asText()).isEqualTo("memory");
        assertThat(meta.get("evidence_count").asInt()).isEqualTo(1);
        assertThat(captor.getValue().getVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("person 事件回注 socialGraph.key_persons：mention_count 累加且不覆盖已有 sentiment")
    void personBackfillsSocialGraphWithoutOverwritingSentiment() throws Exception {
        String existingSocial = """
                {"key_persons":{"妈妈":{"role":"妈妈","sentiment":0.6,"mention_count":2}}}
                """;
        when(profileMapper.selectOne(any())).thenReturn(baseProfile("{}", existingSocial));

        service.backfill(tenantId, userId, List.of(
                new MemoryEvent("person", "和妈妈约定每天聊十分钟", "妈妈", "开心")));

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileMapper).updateById(captor.capture());
        JsonNode social = om.readTree(captor.getValue().getSocialGraph());
        JsonNode mom = social.get("key_persons").get("妈妈");
        assertThat(mom.get("sentiment").asDouble()).isEqualTo(0.6);
        assertThat(mom.get("mention_count").asInt()).isEqualTo(3);
        assertThat(mom.get("last_event").asText()).isEqualTo("和妈妈约定每天聊十分钟");
        assertThat(social.path("_meta").path("key_persons").path("provenance").asText())
                .isEqualTo("memory");
    }

    @Test
    @DisplayName("同内容 milestone 去重：无变化不落库")
    void duplicateMilestoneSkipsUpdate() {
        String existingGrowth = """
                {"milestones":[{"type":"memory_key_event","content":"第一次主动分享了学校的烦恼"}]}
                """;
        when(profileMapper.selectOne(any())).thenReturn(baseProfile(existingGrowth, "{}"));

        service.backfill(tenantId, userId, List.of(
                new MemoryEvent("milestone", "第一次主动分享了学校的烦恼", null, null)));

        verify(profileMapper, never()).updateById(any(StudentProfile.class));
    }

    @Test
    @DisplayName("other 类型 / person 缺 role 不回注")
    void otherTypeAndPersonWithoutRoleAreIgnored() {
        when(profileMapper.selectOne(any())).thenReturn(baseProfile("{}", "{}"));

        service.backfill(tenantId, userId, List.of(
                new MemoryEvent("other", "聊了最近的天气和心情", null, "平静"),
                new MemoryEvent("person", "提到了一位重要的人", null, null)));

        verify(profileMapper, never()).updateById(any(StudentProfile.class));
    }

    @Test
    @DisplayName("画像不存在：静默跳过不炸")
    void noProfileSkipsSilently() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        service.backfill(tenantId, userId, List.of(
                new MemoryEvent("milestone", "第一次尝试了深呼吸放松", null, null)));

        verify(profileMapper, never()).updateById(any(StudentProfile.class));
    }
}
