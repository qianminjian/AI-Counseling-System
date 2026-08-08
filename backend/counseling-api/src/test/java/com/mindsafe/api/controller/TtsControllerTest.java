package com.mindsafe.api.controller;

import com.mindsafe.ai.voice.TtsService;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.service.tts.VoiceDegradationPolicy;
import com.mindsafe.service.voice.VoicePersonaResolver;
import com.mindsafe.service.voice.VoiceRenderProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TtsController 单元测试（P1 覆盖率冲刺：合成/风险降级/声纹引导）
 */
class TtsControllerTest {

    private TtsService ttsService;
    private VoicePersonaResolver personaResolver;
    private VoiceDegradationPolicy degradationPolicy;
    private TtsController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ttsService = mock(TtsService.class);
        personaResolver = mock(VoicePersonaResolver.class);
        degradationPolicy = mock(VoiceDegradationPolicy.class);
        // personaMatcher 测试路径未消费，传 null
        controller = new TtsController(ttsService, personaResolver, degradationPolicy, null);
    }

    private Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userId);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, userId, "student"));
        return auth;
    }

    private VoiceRenderProfile profile() {
        return new VoiceRenderProfile("bobo", "happy", 1.0, 1.0, 0, false, "user_pick", null);
    }

    private byte[] mp3() {
        return new byte[]{(byte) 0xFF, (byte) 0xFB, 0x01, 0x02};
    }

    private byte[] wav() {
        return "RIFFxxxxWAVE".getBytes(StandardCharsets.US_ASCII);
    }

    // ===== 合成 =====

    @Test
    @DisplayName("synthesize 成功 → MP3 音频 + 音色头")
    void synthesize_mp3() {
        when(personaResolver.resolve(tenantId, userId, "bobo", "happy", "chat", null)).thenReturn(profile());
        when(ttsService.synthesize("你好", "bobo", "happy", 1.0, 1.0, 0, null)).thenReturn(mp3());

        ResponseEntity<byte[]> resp = controller.synthesize(
                Map.of("text", "你好", "persona", "bobo", "emotion", "happy"), auth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst("Content-Type")).isEqualTo("audio/mpeg");
        assertThat(resp.getHeaders().getFirst("X-Voice-Persona")).isEqualTo("bobo");
        assertThat(resp.getHeaders().getFirst("X-Voice-Source")).isEqualTo("user_pick");
        assertThat(resp.getBody()).isEqualTo(mp3());
    }

    @Test
    @DisplayName("synthesize WAV 魔数 → audio/wav")
    void synthesize_wav() {
        when(personaResolver.resolve(tenantId, userId, null, "neutral", "chat", null)).thenReturn(profile());
        when(ttsService.synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any())).thenReturn(wav());

        ResponseEntity<byte[]> resp = controller.synthesize(Map.of("text", "你好"), auth());

        assertThat(resp.getHeaders().getFirst("Content-Type")).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("synthesize 文本为空 → 400")
    void synthesize_blankText() {
        ResponseEntity<byte[]> resp = controller.synthesize(Map.of("text", "   "), auth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("synthesize 文本超 500 字上限 → 400 且不调合成（B-02 成本防护）")
    void synthesize_tooLongText() {
        ResponseEntity<byte[]> resp = controller.synthesize(Map.of("text", "好".repeat(501)), auth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(ttsService, never()).synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any());
    }

    @Test
    @DisplayName("synthesize 文本恰好 500 字 → 正常合成（边界）")
    void synthesize_atLimitText() {
        when(personaResolver.resolve(tenantId, userId, null, "neutral", "chat", null)).thenReturn(profile());
        when(ttsService.synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any())).thenReturn(mp3());

        ResponseEntity<byte[]> resp = controller.synthesize(Map.of("text", "好".repeat(500)), auth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("synthesize text 缺失 → 400")
    void synthesize_missingText() {
        ResponseEntity<byte[]> resp = controller.synthesize(Map.of(), auth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("synthesize 无认证 → 匿名合成（userId/tenantId null）")
    void synthesize_anonymous() {
        when(personaResolver.resolve(null, null, null, "neutral", "chat", null)).thenReturn(profile());
        when(ttsService.synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any())).thenReturn(mp3());

        ResponseEntity<byte[]> resp = controller.synthesize(Map.of("text", "你好"), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(personaResolver).resolve(null, null, null, "neutral", "chat", null);
    }

    @Test
    @DisplayName("synthesize 方言透传（language_mode 已废弃 v4，不再传递）")
    void synthesize_dialect() {
        VoiceRenderProfile p = new VoiceRenderProfile("qiqiu", "happy", 1.0, 1.0, 0, false, "auto", "sichuan");
        when(personaResolver.resolve(tenantId, userId, null, "neutral", "chat", "sichuan")).thenReturn(p);
        when(ttsService.synthesize("你好", "qiqiu", "happy", 1.0, 1.0, 0, "sichuan"))
                .thenReturn(mp3());

        controller.synthesize(Map.of("text", "你好", "dialect", "sichuan"), auth());

        verify(ttsService).synthesize("你好", "qiqiu", "happy", 1.0, 1.0, 0, "sichuan");
    }

    @Test
    @DisplayName("synthesize 音频为空 → 204")
    void synthesize_nullAudio() {
        when(personaResolver.resolve(tenantId, userId, null, "neutral", "chat", null)).thenReturn(profile());
        when(ttsService.synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any())).thenReturn(null);

        ResponseEntity<byte[]> resp = controller.synthesize(Map.of("text", "你好"), auth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ===== 风险降级 TTSFX-002 =====

    @Test
    @DisplayName("riskLevel=S0 静默 → 204 不合成")
    void synthesize_silent() {
        when(degradationPolicy.decide("S0", "neutral"))
                .thenReturn(new VoiceDegradationPolicy.VoiceDecision(
                        VoiceDegradationPolicy.VoiceMode.SILENT, null, false, "转热线"));

        ResponseEntity<byte[]> resp = controller.synthesize(
                Map.of("text", "你好", "riskLevel", "S0"), auth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(ttsService, org.mockito.Mockito.never()).synthesize(anyString(), anyString(), anyString(),
                anyDouble(), anyDouble(), anyInt(), any());
    }

    @Test
    @DisplayName("riskLevel=S1 预合成 → 强制安抚情绪 + 语速降 0.9")
    void synthesize_presynthesized() {
        when(degradationPolicy.decide("S1", "neutral"))
                .thenReturn(new VoiceDegradationPolicy.VoiceDecision(
                        VoiceDegradationPolicy.VoiceMode.PRE_SYNTHESIZED, "calm", true, "预合成"));
        when(personaResolver.resolve(tenantId, userId, null, "calm", "chat", null))
                .thenReturn(new VoiceRenderProfile("bobo", "calm", 1.0, 1.0, 0, false, "user_pick", null));
        when(ttsService.synthesize(anyString(), anyString(), eq("calm"), eq(0.9), anyDouble(), anyInt(),
                any())).thenReturn(mp3());

        controller.synthesize(Map.of("text", "你好", "riskLevel", "S1"), auth());

        verify(ttsService).synthesize("你好", "bobo", "calm", 0.9, 1.0, 0, null);
    }

    @Test
    @DisplayName("riskLevel=S2 强制安抚基调 → emotion 被覆盖")
    void synthesize_forcedEmotion() {
        when(degradationPolicy.decide("S2", "angry"))
                .thenReturn(new VoiceDegradationPolicy.VoiceDecision(
                        VoiceDegradationPolicy.VoiceMode.SOOTHE_FORCED, "calm", false, "强制安抚"));
        when(personaResolver.resolve(tenantId, userId, null, "calm", "chat", null))
                .thenReturn(new VoiceRenderProfile("bobo", "calm", 1.0, 1.0, 0, false, "user_pick", null));
        when(ttsService.synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any())).thenReturn(mp3());

        controller.synthesize(Map.of("text", "你好", "riskLevel", "S2", "emotion", "angry"), auth());

        verify(ttsService).synthesize("你好", "bobo", "calm", 1.0, 1.0, 0, null);
    }

    @Test
    @DisplayName("riskLevel=S3 无降级 → 原情绪透传")
    void synthesize_noDegradation() {
        when(degradationPolicy.decide("S3", "happy"))
                .thenReturn(new VoiceDegradationPolicy.VoiceDecision(
                        VoiceDegradationPolicy.VoiceMode.NORMAL, null, false, "正常"));
        when(personaResolver.resolve(tenantId, userId, null, "happy", "chat", null)).thenReturn(profile());
        when(ttsService.synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any())).thenReturn(mp3());

        controller.synthesize(Map.of("text", "你好", "riskLevel", "S3", "emotion", "happy"), auth());

        verify(ttsService).synthesize("你好", "bobo", "happy", 1.0, 1.0, 0, null);
    }

    @Test
    @DisplayName("synthesize 自定义 speed → 与画像 speed 相乘")
    void synthesize_speed() {
        VoiceRenderProfile p = new VoiceRenderProfile("bobo", "neutral", 1.1, 0.8, 0, false, "user_pick", null);
        when(personaResolver.resolve(tenantId, userId, null, "neutral", "chat", null)).thenReturn(p);
        when(ttsService.synthesize(anyString(), anyString(), anyString(), eq(0.8), eq(1.1), anyInt(),
                any())).thenReturn(mp3());

        controller.synthesize(Map.of("text", "你好", "speed", 1.0), auth());

        verify(ttsService).synthesize("你好", "bobo", "neutral", 0.8, 1.1, 0, null);
    }

    // ===== 音色列表 / 状态 =====

    @Test
    @DisplayName("personas 返回音色列表")
    void personas() {
        when(ttsService.getPersonas()).thenReturn(List.of(Map.of("id", "bobo")));

        var resp = controller.personas();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
    }

    @Test
    @DisplayName("status 可用 → cosyvoice2/edge-tts")
    void status_available() {
        when(ttsService.isAvailable()).thenReturn(true);

        var resp = controller.status();

        assertThat(resp.data().get("available")).isEqualTo(true);
        assertThat(resp.data().get("engine")).isEqualTo("cosyvoice2/edge-tts");
    }

    @Test
    @DisplayName("status 不可用 → unavailable")
    void status_unavailable() {
        when(ttsService.isAvailable()).thenReturn(false);

        var resp = controller.status();

        assertThat(resp.data().get("engine")).isEqualTo("unavailable");
    }

    // ===== 声纹登录引导语 =====

    @Test
    @DisplayName("loginPrompt 非白名单文本 → 400")
    void loginPrompt_notWhitelisted() {
        ResponseEntity<byte[]> resp = controller.loginPrompt(Map.of("text", "随便说点什么"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("loginPrompt 白名单文本 → 合成 + 默认 persona xiaoxing")
    void loginPrompt_success() {
        when(ttsService.synthesize("嗨！我是波波，跟我打个招呼吧！", "xiaoxing", "happy", 0.9, 1.0, 0, null))
                .thenReturn(mp3());

        ResponseEntity<byte[]> resp = controller.loginPrompt(Map.of("text", " 嗨！我是波波，跟我打个招呼吧！  "));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst("Cache-Control")).isEqualTo("public, max-age=86400");
        assertThat(resp.getHeaders().getFirst("Content-Type")).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("loginPrompt 非法 persona → 回退 xiaoxing")
    void loginPrompt_badPersona() {
        when(ttsService.synthesize("嗨！我是波波，跟我打个招呼吧！", "xiaoxing", "happy", 0.9, 1.0, 0, null))
                .thenReturn(mp3());

        controller.loginPrompt(Map.of("text", "嗨！我是波波，跟我打个招呼吧！", "persona", "evil"));

        verify(ttsService).synthesize("嗨！我是波波，跟我打个招呼吧！", "xiaoxing", "happy", 0.9, 1.0, 0, null);
    }

    @Test
    @DisplayName("loginPrompt 合成失败 → 204")
    void loginPrompt_nullAudio() {
        when(ttsService.synthesize(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
                anyInt(), any())).thenReturn(null);

        ResponseEntity<byte[]> resp = controller.loginPrompt(Map.of("text", "嗨！我是波波，跟我打个招呼吧！"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

}
