package com.mindsafe.api.controller;

import com.mindsafe.ai.voice.TtsService;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.voice.VoicePersonaResolver;
import com.mindsafe.service.voice.VoiceRenderProfile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TTS 语音合成 API
 * <p>
 * 前端在 AI 回复完成后调用，将文字转为语音播放。
 * TMATCH-001：persona 未指定时由 {@link VoicePersonaResolver} 按画像自动匹配，
 * 情绪同时驱动 prosody 基调（非仅 instruct）。
 */
@RestController
@RequestMapping("/api/v1/tts")
public class TtsController {

    private final TtsService ttsService;
    private final VoicePersonaResolver personaResolver;

    public TtsController(TtsService ttsService, VoicePersonaResolver personaResolver) {
        this.ttsService = ttsService;
        this.personaResolver = personaResolver;
    }

    /**
     * 合成语音（返回音频流）
     */
    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(@RequestBody Map<String, Object> request, Authentication auth) {
        String text = (String) request.get("text");
        // persona 可缺省：前端显式传 = 学生手动选择（最高优先）；缺省 → 画像冷启动自动匹配
        String persona = (String) request.get("persona");
        String emotion = (String) request.getOrDefault("emotion", "neutral");
        String scene = (String) request.getOrDefault("scene", "chat");
        double speed = request.containsKey("speed")
                ? ((Number) request.get("speed")).doubleValue() : 1.0;

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        UUID userId = auth != null && auth.getPrincipal() instanceof UUID id ? id : null;
        UUID tenantId = auth != null && auth.getDetails() instanceof TenantContext ctx ? ctx.tenantId() : null;
        VoiceRenderProfile profile = personaResolver.resolve(tenantId, userId, persona, emotion, scene);

        byte[] audio = ttsService.synthesize(text, profile.persona(), profile.emotionInstruct(),
                speed * profile.speed(), profile.pitchScale(), profile.pauseStyle());
        if (audio == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, detectAudioMimeType(audio))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Voice-Persona", profile.persona())
                .header("X-Voice-Source", profile.source())
                .body(audio);
    }

    /**
     * 根据音频文件魔数（magic bytes）检测真实格式
     * edge-tts 输出 MP3，CosyVoice2 输出 WAV，必须返回正确的 Content-Type
     * 否则 Safari 等严格校验 MIME 的浏览器会解码失败
     */
    private String detectAudioMimeType(byte[] audio) {
        // WAV: 以 RIFF 开头
        if (audio.length >= 4
                && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F') {
            return "audio/wav";
        }
        // MP3: 帧同步头（0xFF Ex/Fx）或 ID3 标签
        boolean isMp3Frame = audio.length >= 2
                && (audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0;
        boolean isId3 = audio.length >= 3
                && audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3';
        if (isMp3Frame || isId3) {
            return "audio/mpeg";
        }
        return "audio/mpeg";
    }

    /**
     * 获取可用音色人设列表
     */
    @GetMapping("/personas")
    public ApiResponse<List<Map<String, Object>>> personas() {
        return ApiResponse.ok(ttsService.getPersonas());
    }

    /**
     * TTS 服务状态
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        boolean available = ttsService.isAvailable();
        return ApiResponse.ok(Map.of(
                "available", available,
                "service", "tts",
                "engine", available ? "cosyvoice2/edge-tts" : "unavailable"
        ));
    }
}
