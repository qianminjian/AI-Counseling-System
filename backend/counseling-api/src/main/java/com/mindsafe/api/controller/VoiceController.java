package com.mindsafe.api.controller;

import com.mindsafe.ai.voice.VoiceAnalysisResult;
import com.mindsafe.ai.voice.VoiceAnalysisService;
import com.mindsafe.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 语音分析 API
 * <p>
 * 接收学生端录音，调用 voice-service 进行 ASR + 情感识别
 * 合规：音频不落盘，处理完即丢弃
 */
@RestController
@RequestMapping("/api/v1/voice")
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    private final VoiceAnalysisService voiceAnalysisService;

    public VoiceController(VoiceAnalysisService voiceAnalysisService) {
        this.voiceAnalysisService = voiceAnalysisService;
    }

    /**
     * 分析语音消息
     * <p>
     * 前端录音后上传音频文件，返回转写文字 + 情绪标签
     * 前端拿到 text 后走正常的 sendMessage 流程
     */
    @PostMapping("/analyze")
    public ApiResponse<Map<String, Object>> analyzeVoice(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "音频文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            return ApiResponse.error(400, "仅支持音频文件");
        }

        byte[] audioBytes = file.getBytes();
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.webm";

        VoiceAnalysisResult result = voiceAnalysisService.analyze(audioBytes, filename, contentType);

        Map<String, Object> data = Map.of(
                "text", result.text(),
                "emotion", Map.of(
                        "label", result.emotion().label(),
                        "labelEn", result.emotion().labelEn(),
                        "confidence", result.emotion().confidence(),
                        "scores", result.emotion().scores()
                ),
                "durationSeconds", result.durationSeconds(),
                "isNegative", result.isNegativeEmotion(),
                "emotionRiskLevel", result.emotionRiskLevel()
        );

        return ApiResponse.ok(data);
    }

    /**
     * 检查语音服务可用性
     * <p>
     * 模型名透传自 voice-service /health，不硬编码（模型可配置切换）
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> health = voiceAnalysisService.fetchHealth();
        if (health == null) {
            return ApiResponse.ok(Map.of(
                    "available", false,
                    "service", "voice-analysis",
                    "models", "unavailable"
            ));
        }
        String asrModel = String.valueOf(health.getOrDefault("asr_model", "unknown"));
        String serModel = String.valueOf(health.getOrDefault("ser_model", "unknown"));
        return ApiResponse.ok(Map.of(
                "available", true,
                "service", "voice-analysis",
                "models", asrModel + " + " + serModel
        ));
    }
}
