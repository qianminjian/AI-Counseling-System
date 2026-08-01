package com.mindsafe.service.voice;

import com.mindsafe.ai.orchestrator.ProfileSignals;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.profile.StudentProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VoicePersonaResolver 单元测试（TMATCH-001，design/48 §5.1/§6.1/§6.3）
 * <p>
 * 覆盖：冷启动默认匹配、手动偏好优先、情绪→prosody 基调、分龄基调、
 * 安全/危机场景锁定红线、失败安全回落。
 */
@ExtendWith(MockitoExtension.class)
class VoicePersonaResolverTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private StudentProfileService profileService;

    private VoicePersonaResolver resolver;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new VoicePersonaResolver(userMapper, profileService);
    }

    private User student(String gradeCode, String gender) {
        User u = new User();
        u.setGradeCode(gradeCode);
        u.setGender(gender);
        return u;
    }

    // ==================== 冷启动默认匹配（design/48 §5.1） ====================

    @Test
    @DisplayName("冷启动：低龄一年级 → yueliang 讲故事音色")
    void coldStart_lowGrade_yueliang() {
        when(userMapper.selectOne(any())).thenReturn(student("G1", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("yueliang");
        assertThat(profile.source()).isEqualTo("default");
        // 低龄不再查画像（低龄规则优先，减少无谓查询）
        verify(profileService, never()).getProfileSignals(any(), any());
    }

    @Test
    @DisplayName("冷启动：男生+五年级 → xiaotaiyang 大哥哥音色")
    void coldStart_maleUpperGrade_xiaotaiyang() {
        when(userMapper.selectOne(any())).thenReturn(student("G5", "male"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("xiaotaiyang");
        assertThat(profile.source()).isEqualTo("default");
    }

    @Test
    @DisplayName("冷启动：女生四年级+高置信外向画像 → qiqiu，source=profile")
    void coldStart_extrovertProfile_qiqiu() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));
        when(profileService.getProfileSignals(tenantId, userId))
                .thenReturn(new ProfileSignals(0.2, 0.8, List.of(), 0));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("qiqiu");
        assertThat(profile.source()).isEqualTo("profile");
    }

    @Test
    @DisplayName("冷启动：低置信外向画像不参与决策 → xiaoxing（置信门控与编排同源）")
    void coldStart_lowConfidenceProfile_ignored() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));
        when(profileService.getProfileSignals(tenantId, userId))
                .thenReturn(new ProfileSignals(0.2, 0.3, List.of(), 0));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("xiaoxing");
        assertThat(profile.source()).isEqualTo("default");
    }

    @Test
    @DisplayName("冷启动：无画像的女生中年级 → xiaoxing 通用默认")
    void coldStart_noProfile_xiaoxing() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));
        when(profileService.getProfileSignals(tenantId, userId)).thenReturn(null);

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("xiaoxing");
        assertThat(profile.source()).isEqualTo("default");
    }

    // ==================== 失败安全回落 ====================

    @Test
    @DisplayName("失败安全：用户查询异常 → xiaoxing 默认，不阻塞播放")
    void failSafe_userQueryError_fallback() {
        when(userMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("xiaoxing");
        assertThat(profile.source()).isEqualTo("default");
    }

    @Test
    @DisplayName("失败安全：画像查询异常 → xiaoxing 默认")
    void failSafe_profileQueryError_fallback() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));
        when(profileService.getProfileSignals(tenantId, userId))
                .thenThrow(new RuntimeException("profile error"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("xiaoxing");
        assertThat(profile.source()).isEqualTo("default");
    }

    @Test
    @DisplayName("失败安全：无认证上下文（tenantId/userId 为 null）→ xiaoxing 默认")
    void failSafe_missingAuth_fallback() {
        VoiceRenderProfile profile = resolver.resolve(null, null, null, "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("xiaoxing");
        assertThat(profile.source()).isEqualTo("default");
        verify(userMapper, never()).selectOne(any());
    }

    // ==================== 手动偏好优先 ====================

    @Test
    @DisplayName("手动偏好最高优先：男生五年级手动选 qiqiu → qiqiu，source=manual")
    void manualPersona_overridesColdStart() {
        when(userMapper.selectOne(any())).thenReturn(student("G5", "male"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "qiqiu", "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("qiqiu");
        assertThat(profile.source()).isEqualTo("manual");
    }

    @Test
    @DisplayName("非法 persona 回落自动匹配（不透传未知音色给 tts-service）")
    void invalidPersona_fallsBackToColdStart() {
        when(userMapper.selectOne(any())).thenReturn(student("G5", "male"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "foo", "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("xiaotaiyang");
        assertThat(profile.source()).isEqualTo("default");
    }

    // ==================== 7 音色矩阵（design/56） ====================

    @Test
    @DisplayName("新增音色 bobo 可手动选择")
    void manualPersona_bobo_valid() {
        when(userMapper.selectOne(any())).thenReturn(student("G3", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "bobo", "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("bobo");
        assertThat(profile.source()).isEqualTo("manual");
    }

    @Test
    @DisplayName("新增音色 dashu 可手动选择")
    void manualPersona_dashu_valid() {
        when(userMapper.selectOne(any())).thenReturn(student("G5", "male"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "dashu", "neutral", "chat", null);

        assertThat(profile.persona()).isEqualTo("dashu");
        assertThat(profile.source()).isEqualTo("manual");
    }

    @Test
    @DisplayName("新增音色 doudou 可手动选择")
    void manualPersona_doudou_valid() {
        when(userMapper.selectOne(any())).thenReturn(student("G2", "male"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "doudou", "happy", "chat", null);

        assertThat(profile.persona()).isEqualTo("doudou");
        assertThat(profile.source()).isEqualTo("manual");
    }

    // ==================== dialect 透传（design/56 §三） ====================

    @Test
    @DisplayName("dialect 透传：前端传 sichuan → profile.dialect()=sichuan")
    void dialect_passthrough_sichuan() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "qiqiu", "neutral", "chat", "sichuan");

        assertThat(profile.dialect()).isEqualTo("sichuan");
    }

    @Test
    @DisplayName("dialect 为 null → profile.dialect()=null")
    void dialect_null_passthrough() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.dialect()).isNull();
    }

    @Test
    @DisplayName("安全场景锁定也透传 dialect")
    void dialect_safetyScene_stillPassthrough() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "qiqiu", "happy", "safety", "cantonese");

        assertThat(profile.locked()).isTrue();
        assertThat(profile.dialect()).isEqualTo("cantonese");
    }

    // ==================== 情绪 → prosody 基调（design/48 §6.1） ====================

    @Test
    @DisplayName("ACTIVATED 情绪（sad）→ 安抚基调：音高更低、更慢、多停顿")
    void prosody_sadEmotion_soothing() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "sad", "chat", null);

        assertThat(profile.emotionInstruct()).isEqualTo("sad");
        assertThat(profile.pitchScale()).isEqualTo(0.9);
        assertThat(profile.speed()).isEqualTo(0.9);
        assertThat(profile.pauseStyle()).isEqualTo(2);
        assertThat(profile.locked()).isFalse();
    }

    @Test
    @DisplayName("happy 情绪 → 轻快基调：音高略高、无额外停顿")
    void prosody_happyEmotion_bright() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "happy", "chat", null);

        assertThat(profile.pitchScale()).isEqualTo(1.05);
        assertThat(profile.speed()).isEqualTo(1.0);
        assertThat(profile.pauseStyle()).isEqualTo(0);
    }

    @Test
    @DisplayName("neutral 情绪 → 自然基调")
    void prosody_neutralEmotion_natural() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.pitchScale()).isEqualTo(1.0);
        assertThat(profile.speed()).isEqualTo(1.0);
        assertThat(profile.pauseStyle()).isEqualTo(1);
    }

    @Test
    @DisplayName("情绪缺失 → instruct 回退 neutral")
    void prosody_blankEmotion_defaultsNeutral() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "", "chat", null);

        assertThat(profile.emotionInstruct()).isEqualTo("neutral");
        assertThat(profile.pauseStyle()).isEqualTo(1);
    }

    // ==================== 分龄基调（design/48 §5.2） ====================

    @Test
    @DisplayName("低龄一年级+neutral → 更慢（×0.92）、停顿升级到安抚档")
    void prosody_lowGrade_slowerWithMorePauses() {
        when(userMapper.selectOne(any())).thenReturn(student("G1", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "neutral", "chat", null);

        assertThat(profile.speed()).isCloseTo(0.92, within(0.001));
        assertThat(profile.pauseStyle()).isEqualTo(2);
    }

    // ==================== 安全/危机场景锁定（design/48 §6.3 红线） ====================

    @Test
    @DisplayName("safety 场景：即使 happy 也锁定稳定基调 + 中性 instruct（不干扰合规话术）")
    void safetyScene_locksStableTone() {
        when(userMapper.selectOne(any())).thenReturn(student("G4", "female"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, null, "happy", "safety", null);

        assertThat(profile.locked()).isTrue();
        assertThat(profile.emotionInstruct()).isEqualTo("neutral");
        assertThat(profile.pitchScale()).isEqualTo(0.95);
        assertThat(profile.speed()).isEqualTo(0.88);
        assertThat(profile.pauseStyle()).isEqualTo(2);
    }

    @Test
    @DisplayName("crisis 场景同样锁定，且保留 persona 决策结果")
    void crisisScene_locksAndKeepsPersona() {
        when(userMapper.selectOne(any())).thenReturn(student("G5", "male"));

        VoiceRenderProfile profile = resolver.resolve(tenantId, userId, "yueliang", "angry", "crisis", null);

        assertThat(profile.locked()).isTrue();
        assertThat(profile.persona()).isEqualTo("yueliang");
        assertThat(profile.source()).isEqualTo("manual");
        assertThat(profile.emotionInstruct()).isEqualTo("neutral");
    }
}
