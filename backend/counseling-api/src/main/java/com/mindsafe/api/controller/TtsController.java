package com.mindsafe.api.controller;

import com.mindsafe.ai.voice.TtsService;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.tts.VoiceDegradationPolicy;
import com.mindsafe.service.tts.VoicePersonaMatcher;
import com.mindsafe.service.voice.VoicePersonaResolver;
import com.mindsafe.service.voice.VoiceRenderProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TTS 语音合成 API
 * <p>
 * 前端在 AI 回复完成后调用，将文字转为语音播放。
 * TMATCH-001：persona 未指定时由 {@link VoicePersonaResolver} 按画像自动匹配，
 * 情绪同时驱动 prosody 基调（非仅 instruct）。
 * TTSFX-002：风险场景由 {@link VoiceDegradationPolicy} 决定语音输出模式（S1 预合成/S0 静默）。
 * TMATCH-002：安全/危机场景由 {@link VoicePersonaMatcher} 锁定稳定基调。
 */
@RestController
@RequestMapping("/api/v1/tts")
public class TtsController {

    private static final Logger log = LoggerFactory.getLogger(TtsController.class);

    /** 合成文本长度上限（字符，B-02：超长文本烧 edge-tts/CosyVoice 配额与算力，限流拦截器兜底） */
    private static final int MAX_TEXT_LENGTH = 500;

    private final TtsService ttsService;
    private final VoicePersonaResolver personaResolver;
    private final VoiceDegradationPolicy degradationPolicy;
    private final VoicePersonaMatcher personaMatcher;

    public TtsController(TtsService ttsService, VoicePersonaResolver personaResolver,
                         VoiceDegradationPolicy degradationPolicy, VoicePersonaMatcher personaMatcher) {
        this.ttsService = ttsService;
        this.personaResolver = personaResolver;
        this.degradationPolicy = degradationPolicy;
        this.personaMatcher = personaMatcher;
    }

    /**
     * 合成语音（返回音频流）
     * <p>
     * TTSFX-002：传入 riskLevel 时启用风险降级策略：
     * S0/RED → 静默（返回 204）；S1/ORANGE → 预合成安抚话术；S2/YELLOW → 强制安抚基调。
     */
    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(@RequestBody Map<String, Object> request, Authentication auth) {
        String text = (String) request.get("text");
        // persona 可缺省：前端显式传 = 学生手动选择（最高优先）；缺省 → 画像冷启动自动匹配
        String persona = (String) request.get("persona");
        String emotion = (String) request.getOrDefault("emotion", "neutral");
        String scene = (String) request.getOrDefault("scene", "chat");
        String riskLevel = (String) request.get("riskLevel"); // TTSFX-002：可选风险等级
        String dialect = (String) request.get("dialect"); // design/56：方言代码（可选，仅方言音色 qiqiu 生效）
        double speed = request.containsKey("speed")
                ? ((Number) request.get("speed")).doubleValue() : 1.0;

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // B-02：文本长度上限校验（与 RateLimitInterceptor tts_synthesize 限流双层防护）
        if (text.length() > MAX_TEXT_LENGTH) {
            log.warn("TTS 文本超长已拒绝: 长度={}, 上限={}", text.length(), MAX_TEXT_LENGTH);
            return ResponseEntity.badRequest().build();
        }

        // TTSFX-002：风险场景语音降级策略
        if (riskLevel != null) {
            VoiceDegradationPolicy.VoiceDecision decision = degradationPolicy.decide(riskLevel, emotion);
            log.info("TTS 风险降级决策: riskLevel={}, mode={}, reason={}", riskLevel, decision.mode(), decision.reason());

            if (decision.mode() == VoiceDegradationPolicy.VoiceMode.SILENT) {
                // S0：转热线后不再播放语音
                return ResponseEntity.noContent().build();
            }
            if (decision.preSynthesized()) {
                // S1：使用预合成安抚话术库（零延迟+零合成事故）
                // 当前版本：预合成音频库未建立，降级为强制安抚基调实时合成
                emotion = decision.forcedEmotion() != null ? decision.forcedEmotion() : "calm";
                speed = Math.min(speed, 0.9); // 语速降至下限
            } else if (decision.forcedEmotion() != null) {
                // S2：强制安抚基调
                emotion = decision.forcedEmotion();
            }
        }

        UUID userId = auth != null && auth.getPrincipal() instanceof UUID id ? id : null;
        UUID tenantId = auth != null && auth.getDetails() instanceof TenantContext ctx ? ctx.tenantId() : null;
        VoiceRenderProfile profile = personaResolver.resolve(tenantId, userId, persona, emotion, scene, dialect);

        byte[] audio = ttsService.synthesize(text, profile.persona(), profile.emotionInstruct(),
                speed * profile.speed(), profile.pitchScale(), profile.pauseStyle(), profile.dialect());
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
     * 声纹登录引导语 TTS（公开端点，无需认证）
     * <p>
     * 声纹登录发生在用户未登录状态，无法走 /synthesize（需 JWT）。
     * 本端点仅允许白名单内的固定引导语文本，防止滥用。
     */
    private static final Set<String> LOGIN_PROMPT_WHITELIST = Set.of(
            // verify 模式
            "嗨！我是波波，跟我打个招呼吧！",
            "真棒！再跟我说一句：今天心情真好呀！",
            // enroll 模式
            "嗨！我是波波，很高兴认识你！跟我打个招呼吧！",
            "真好听！再跟我说一句：我喜欢唱歌和画画！",
            "最后一句啦！跟我说：今天天气真好，我想出去玩！"
    );

    @PostMapping("/login-prompt")
    public ResponseEntity<byte[]> loginPrompt(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        String persona = request.getOrDefault("persona", "xiaoxing");

        if (text == null || !LOGIN_PROMPT_WHITELIST.contains(text.trim())) {
            return ResponseEntity.badRequest().build();
        }
        // 仅允许合法 persona（design/56 7 音色）
        if (!Set.of("xiaoxing", "bobo", "yueliang", "xiaotaiyang", "dashu", "doudou", "qiqiu").contains(persona)) {
            persona = "xiaoxing";
        }

        byte[] audio = ttsService.synthesize(text.trim(), persona, "happy", 0.9, 1.0, 0, null);
        if (audio == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, detectAudioMimeType(audio))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(audio);
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
