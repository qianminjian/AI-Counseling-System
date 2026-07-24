package com.mindsafe.api.controller;

import com.mindsafe.ai.voice.TtsService;
import com.mindsafe.common.dto.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TTS 语音合成 API
 * <p>
 * 前端在 AI 回复完成后调用，将文字转为语音播放
 */
@RestController
@RequestMapping("/api/v1/tts")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    /**
     * 合成语音（返回音频流）
     */
    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(@RequestBody Map<String, Object> request) {
        String text = (String) request.get("text");
        String persona = (String) request.getOrDefault("persona", "xiaoxing");
        String emotion = (String) request.getOrDefault("emotion", "neutral");
        double speed = request.containsKey("speed")
                ? ((Number) request.get("speed")).doubleValue() : 1.0;

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        byte[] audio = ttsService.synthesize(text, persona, emotion, speed);
        if (audio == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(audio);
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
