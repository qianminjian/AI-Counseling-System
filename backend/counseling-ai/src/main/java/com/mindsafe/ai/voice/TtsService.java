package com.mindsafe.ai.voice;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
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
    private final Timer ttsStreamTimer;
    private final Counter ttsErrorCounter;

    public TtsService(@Value("${mindsafe.tts-service.url:http://localhost:10096}") String baseUrl,
                      MeterRegistry meterRegistry) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        this.ttsStreamTimer = Timer.builder("mindsafe.tts.stream_duration")
                .description("TTS 流式合成总耗时")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(meterRegistry);
        this.ttsErrorCounter = Counter.builder("mindsafe.tts.error")
                .description("TTS 合成失败次数")
                .register(meterRegistry);
    }

    /** TTS 合成超时（秒）：超过此时间未返回则静默降级，避免前端无限等待 */
    private static final Duration SYNTH_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 合成语音（返回音频二进制）
     *
     * @param text       要合成的文本
     * @param persona    音色人设（xiaoxing/qiqiu/yueliang/xiaotaiyang）
     * @param emotion    孩子当前情绪
     * @param speed      语速倍率
     * @param pitch      音高基调（TMATCH-001 prosody，1.0=自然）
     * @param pauseStyle 停顿风格（0=轻快 1=自然 2=多停顿安抚）
     * @return 音频字节数组（wav/mp3）
     */
    public byte[] synthesize(String text, String persona, String emotion, double speed,
                             double pitch, int pauseStyle) {
        try {
            Map<String, Object> body = Map.of(
                    "text", text,
                    "persona", persona != null ? persona : "xiaoxing",
                    "emotion", emotion != null ? emotion : "neutral",
                    "speed", speed,
                    "pitch", pitch,
                    "pause_style", pauseStyle
            );

            byte[] audio = webClient.post()
                    .uri("/api/v1/tts/synthesize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(SYNTH_TIMEOUT)
                    .block();

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
     * 流式合成语音（PERF-003：边合成边返回，不等待全部音频完成）
     * <p>
     * 相比 {@link #synthesize}，本方法将 Python tts-service 的 StreamingResponse
     * 直接透传给调用方，消除 Java 层“收完再发”的缓冲延迟。
     *
     * @return 音频字节流（Flux，每个元素是一个网络 chunk）
     */
    public Flux<byte[]> synthesizeStream(String text, String persona, String emotion, double speed,
                                         double pitch, int pauseStyle) {
        Map<String, Object> body = Map.of(
                "text", text,
                "persona", persona != null ? persona : "xiaoxing",
                "emotion", emotion != null ? emotion : "neutral",
                "speed", speed,
                "pitch", pitch,
                "pause_style", pauseStyle
        );

        long startTime = System.currentTimeMillis();
        return webClient.post()
                .uri("/api/v1/tts/synthesize")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .timeout(SYNTH_TIMEOUT)
                .doOnComplete(() -> {
                    ttsStreamTimer.record(Duration.ofMillis(System.currentTimeMillis() - startTime));
                    log.debug("TTS 流式合成完成: text_len={}", text.length());
                })
                .onErrorResume(e -> {
                    ttsErrorCounter.increment();
                    log.error("TTS 流式合成失败（静默降级）: {}", e.getMessage());
                    return Flux.empty();
                });
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
                    .block();
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
                    .block();
            return health != null && "UP".equals(health.get("status"));
        } catch (Exception e) {
            return false;
        }
    }
}
