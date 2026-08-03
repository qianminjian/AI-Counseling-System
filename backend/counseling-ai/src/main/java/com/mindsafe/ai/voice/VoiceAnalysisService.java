package com.mindsafe.ai.voice;

import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 语音分析服务（调用 Python voice-service 微服务）
 * <p>
 * 功能：ASR 转文字 + emotion2vec+ 情感识别
 * 合规：音频仅流式传输到本地微服务，不落盘
 */
@Service
public class VoiceAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(VoiceAnalysisService.class);

    private final WebClient webClient;
    /** block() 兑底超时（略大于连接/响应超时，避免无限阻塞调用线程） */
    private final Duration blockTimeout;

    public VoiceAnalysisService(
            @Value("${mindsafe.voice-service.url:http://localhost:10095}") String baseUrl,
            @Value("${mindsafe.voice-service.timeout-seconds:15}") long timeoutSeconds) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) (timeoutSeconds * 1000))
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));
        this.blockTimeout = Duration.ofSeconds(timeoutSeconds + 5);
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 分析语音：ASR + 情感识别
     *
     * @param audioBytes  音频二进制数据
     * @param filename    原始文件名（用于推断格式）
     * @param contentType MIME 类型（audio/webm, audio/wav 等）
     * @return 分析结果（文字 + 情绪），服务不可用时返回 fallback
     */
    @SuppressWarnings("unchecked")
    public VoiceAnalysisResult analyze(byte[] audioBytes, String filename, String contentType) {
        try {
            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("file", new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }).contentType(MediaType.parseMediaType(contentType));

            Map<String, Object> response = webClient.post()
                    .uri("/api/v1/voice/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(bodyBuilder.build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(blockTimeout);

            if (response == null) {
                log.error("语音分析服务返回空");
                return fallbackResult();
            }

            String text = (String) response.getOrDefault("text", "");
            Map<String, Object> emotionMap = (Map<String, Object>) response.get("emotion");
            double duration = ((Number) response.getOrDefault("duration_seconds", 0.0)).doubleValue();
            VoiceAnalysisResult.EmotionInfo emotion = parseEmotion(emotionMap);

            VoiceAnalysisResult result = new VoiceAnalysisResult(text, emotion, duration);
            log.info("语音分析完成: text_len={}, emotion={}({}), duration={}s",
                    text.length(), emotion.labelEn(),
                    String.format("%.2f", emotion.confidence()), duration);
            return result;

        } catch (Exception e) {
            log.error("语音分析服务调用失败（降级为纯文本模式）", e);
            return fallbackResult();
        }
    }

    /**
     * 检查语音服务是否可用
     */
    @SuppressWarnings("unchecked")
    public boolean isAvailable() {
        try {
            webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private VoiceAnalysisResult.EmotionInfo parseEmotion(Map<String, Object> emotionMap) {
        if (emotionMap == null) {
            return new VoiceAnalysisResult.EmotionInfo("未知", "unknown", 0.0, List.of());
        }
        String label = (String) emotionMap.getOrDefault("label", "未知");
        String labelEn = (String) emotionMap.getOrDefault("label_en", "unknown");
        double confidence = ((Number) emotionMap.getOrDefault("confidence", 0.0)).doubleValue();
        List<Double> scores = ((List<Number>) emotionMap.getOrDefault("scores", List.of()))
                .stream().map(Number::doubleValue).toList();
        return new VoiceAnalysisResult.EmotionInfo(label, labelEn, confidence, scores);
    }

    private VoiceAnalysisResult fallbackResult() {
        return new VoiceAnalysisResult(
                "", new VoiceAnalysisResult.EmotionInfo("未知", "unknown", 0.0, List.of()), 0.0);
    }
}
