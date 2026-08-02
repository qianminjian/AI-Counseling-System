package com.mindsafe.api.controller;

import com.mindsafe.ai.voice.TtsService;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.service.tts.VoiceDegradationPolicy;
import com.mindsafe.service.tts.VoicePersonaMatcher;
import com.mindsafe.service.tts.TtsPipelineScheduler;
import com.mindsafe.service.tts.VoiceEffectivenessTracker;
import com.mindsafe.service.voice.VoicePersonaResolver;
import com.mindsafe.service.voice.VoiceRenderProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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

    private final TtsService ttsService;
    private final VoicePersonaResolver personaResolver;
    private final VoiceDegradationPolicy degradationPolicy;
    private final VoicePersonaMatcher personaMatcher;
    private final TtsPipelineScheduler pipelineScheduler;
    private final VoiceEffectivenessTracker effectivenessTracker;

    public TtsController(TtsService ttsService, VoicePersonaResolver personaResolver,
                         VoiceDegradationPolicy degradationPolicy, VoicePersonaMatcher personaMatcher,
                         TtsPipelineScheduler pipelineScheduler, VoiceEffectivenessTracker effectivenessTracker) {
        this.ttsService = ttsService;
        this.personaResolver = personaResolver;
        this.degradationPolicy = degradationPolicy;
        this.personaMatcher = personaMatcher;
        this.pipelineScheduler = pipelineScheduler;
        this.effectivenessTracker = effectivenessTracker;
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
        double speed = request.containsKey("speed")
                ? ((Number) request.get("speed")).doubleValue() : 1.0;

        if (text == null || text.isBlank()) {
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
     * 流式合成语音（PERF-003：边合成边返回，消除 Java 层缓冲延迟）
     * <p>
     * 前端 fetch 此端点时，浏览器会在 Python tts-service 产出第一个音频 chunk 时
     * 就开始接收数据，而非等待 Java 收完整段音频再转发。
     */
    @PostMapping(value = "/synthesize-stream", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Flux<byte[]>> synthesizeStream(@RequestBody Map<String, Object> request, Authentication auth) {
        String text = (String) request.get("text");
        String persona = (String) request.get("persona");
        String emotion = (String) request.getOrDefault("emotion", "neutral");
        String scene = (String) request.getOrDefault("scene", "chat");
        String riskLevel = (String) request.get("riskLevel");
        double speed = request.containsKey("speed")
                ? ((Number) request.get("speed")).doubleValue() : 1.0;

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // 风险降级策略（同 /synthesize）
        if (riskLevel != null) {
            VoiceDegradationPolicy.VoiceDecision decision = degradationPolicy.decide(riskLevel, emotion);
            if (decision.mode() == VoiceDegradationPolicy.VoiceMode.SILENT) {
                return ResponseEntity.noContent().build();
            }
            if (decision.preSynthesized()) {
                emotion = decision.forcedEmotion() != null ? decision.forcedEmotion() : "calm";
                speed = Math.min(speed, 0.9);
            } else if (decision.forcedEmotion() != null) {
                emotion = decision.forcedEmotion();
            }
        }

        UUID userId = auth != null && auth.getPrincipal() instanceof UUID id ? id : null;
        UUID tenantId = auth != null && auth.getDetails() instanceof TenantContext ctx ? ctx.tenantId() : null;
        VoiceRenderProfile profile = personaResolver.resolve(tenantId, userId, persona, emotion, scene);

        Flux<byte[]> audioStream = ttsService.synthesizeStream(
                text, profile.persona(), profile.emotionInstruct(),
                speed * profile.speed(), profile.pitchScale(), profile.pauseStyle());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header("X-Voice-Persona", profile.persona())
                .header("X-Voice-Source", profile.source())
                .body(audioStream);
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
        // 仅允许合法 persona
        if (!Set.of("xiaoxing", "qiqiu", "yueliang", "xiaotaiyang").contains(persona)) {
            persona = "xiaoxing";
        }

        byte[] audio = ttsService.synthesize(text.trim(), persona, "happy", 0.9, 1.0, 0);
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

    // ===== TTSFX-003：延迟流水线 + 帧率性能自动降级 =====

    /**
     * TTS 流水线调度（句子级延迟预算评估）
     */
    @PostMapping("/pipeline/schedule")
    public ApiResponse<Map<String, Object>> schedulePipeline(@RequestBody Map<String, Object> request) {
        String text = (String) request.getOrDefault("text", "");
        int sentenceIndex = request.containsKey("sentenceIndex")
                ? ((Number) request.get("sentenceIndex")).intValue() : 0;
        boolean isLast = Boolean.TRUE.equals(request.get("isLast"));

        TtsPipelineScheduler.SentenceTask task = new TtsPipelineScheduler.SentenceTask(
                sentenceIndex, text, sentenceIndex == 0, false);
        TtsPipelineScheduler.ScheduleResult result = pipelineScheduler.schedule(task);

        return ApiResponse.ok(Map.of(
                "sentenceIndex", result.sentenceIndex(),
                "immediatePlay", result.immediatePlay(),
                "parallelSynth", result.parallelSynth(),
                "strategy", result.strategy()));
    }

    // ===== TMATCH-003：音色效果回收 =====

    /**
     * 音色效果评估（完成率/切换/参与度）
     */
    @PostMapping("/effectiveness/evaluate")
    public ApiResponse<Map<String, Object>> evaluateEffectiveness(@RequestBody Map<String, Object> request) {
        String voiceId = (String) request.getOrDefault("voiceId", "default");
        int totalSessions = request.containsKey("totalSessions")
                ? ((Number) request.get("totalSessions")).intValue() : 0;
        double avgCompletion = request.containsKey("avgCompletionRate")
                ? ((Number) request.get("avgCompletionRate")).doubleValue() : 0;
        int switchCount = request.containsKey("manualSwitchCount")
                ? ((Number) request.get("manualSwitchCount")).intValue() : 0;

        VoiceEffectivenessTracker.VoiceMetrics metrics = new VoiceEffectivenessTracker.VoiceMetrics(
                voiceId, totalSessions, avgCompletion, switchCount, 0, 0);
        VoiceEffectivenessTracker.EffectivenessVerdict verdict = effectivenessTracker.evaluate(metrics);

        return ApiResponse.ok(Map.of(
                "effective", verdict.effective(),
                "reason", verdict.reason(),
                "suggestRuleChange", verdict.suggestRuleChange()));
    }
}
