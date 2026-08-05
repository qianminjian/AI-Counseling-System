package com.mindsafe.ai.voice;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * TTS 语音合成服务（调用 Python tts-service）
 * <p>
 * 功能：文本 → 情感语音（CosyVoice2 / edge-tts 降级）
 * 支持：多音色人设 + 情绪自适应 + 语速年龄适配
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final WebClient webClient;

    public TtsService(@Value("${mindsafe.tts-service.url:http://localhost:10096}") String baseUrl,
                      MeterRegistry meterRegistry) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    /** TTS 合成超时（秒）：超过此时间未返回则静默降级，避免前端无限等待 */
    private static final Duration SYNTH_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 合成语音（返回音频二进制）
     *
     * @param text         要合成的文本
     * @param persona      音色人设（xiaoxing/bobo/yueliang/xiaotaiyang/dashu/doudou/qiqiu）
     * @param emotion      孩子当前情绪
     * @param speed        语速倍率
     * @param pitch        音高基调（TMATCH-001 prosody，1.0=自然）
     * @param pauseStyle   停顿风格（0=轻快 1=自然 2=多停顿安抚）
     * @param dialect      方言代码（可为 null，仅方言音色 qiqiu 生效，design/56）
     * @return 音频字节数组（wav/mp3）
     * <p>
     * D1（2026-08-05）：移除已废弃的 languageMode 参数——tts-service v4 起原生方言自动生效，
     * language_mode 不再被读取，Java 侧不再传递。
     */
    public byte[] synthesize(String text, String persona, String emotion, double speed,
                             double pitch, int pauseStyle, String dialect) {
        try {
            var bodyBuilder = new java.util.HashMap<String, Object>();
            bodyBuilder.put("text", text);
            bodyBuilder.put("persona", persona != null ? persona : "xiaoxing");
            bodyBuilder.put("emotion", emotion != null ? emotion : "neutral");
            bodyBuilder.put("speed", speed);
            bodyBuilder.put("pitch", pitch);
            bodyBuilder.put("pause_style", pauseStyle);
            if (dialect != null && !dialect.isBlank()) {
                bodyBuilder.put("dialect", dialect);
            }
            Map<String, Object> body = bodyBuilder;

            byte[] audio = webClient.post()
                    .uri("/api/v1/tts/synthesize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(SYNTH_TIMEOUT)
                    .block(SYNTH_TIMEOUT.plusSeconds(5));

            if (audio != null && audio.length > 0) {
                log.debug("TTS 合成成功: text_len={}, audio_len={}", text.length(), audio.length);
                return audio;
            }
            return null;
        } catch (Exception e) {
            log.error("TTS 合成失败（静默降级）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取可用音色列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPersonas() {
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/api/v1/tts/personas")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            if (response != null && response.containsKey("data")) {
                return (List<Map<String, Object>>) response.get("data");
            }
        } catch (Exception e) {
            log.error("获取音色列表失败", e);
        }
        return List.of();
    }

    /**
     * 检查 TTS 服务是否可用
     */
    @SuppressWarnings("unchecked")
    public boolean isAvailable() {
        try {
            Map<String, Object> health = webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            return health != null && "UP".equals(health.get("status"));
        } catch (Exception e) {
            return false;
        }
    }
}
