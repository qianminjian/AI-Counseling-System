package com.mindsafe.ai.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VoiceAnalysisService 单测（AI-007 / 54_语音情感分析设计方案）。
 * <p>
 * 构造器自建 WebClient（不可注入），测试策略：
 * <ul>
 *   <li>成功/解析路径：ReflectionTestUtils 注入深桩 WebClient</li>
 *   <li>降级路径：真实实例 + 不可达地址（localhost:1，连接拒绝即时失败）</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@DisplayName("语音分析服务（ASR + emotion2vec+）")
class VoiceAnalysisServiceTest {

    private static final String ANALYZE_URI = "/api/v1/voice/analyze";
    private static final String HEALTH_URI = "/health";

    private VoiceAnalysisService service;

    @BeforeEach
    void setUp() {
        // 默认指向不可达地址；成功路径用注入替换 webClient
        service = new VoiceAnalysisService("http://localhost:1", 2);
    }

    /** 注入深桩 WebClient 并返回它，供 when(...) 打桩 */
    private WebClient injectDeepClient() {
        WebClient deepClient = mock(WebClient.class, Answers.RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(service, "webClient", deepClient);
        return deepClient;
    }

    @Nested
    @DisplayName("analyze 成功路径")
    class AnalyzeSuccess {

        @Test
        @DisplayName("完整响应 → 解析 text/emotion/duration")
        void fullResponse_parsed() {
            WebClient deep = injectDeepClient();
            Map<String, Object> emotion = new HashMap<>();
            emotion.put("label", "悲伤");
            emotion.put("label_en", "sad");
            emotion.put("confidence", 0.85);
            emotion.put("scores", List.of(0.1, 0.2));
            Map<String, Object> resp = new HashMap<>();
            resp.put("text", "我不开心");
            resp.put("emotion", emotion);
            resp.put("duration_seconds", 3.2);

            when(deep.post().uri(ANALYZE_URI)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(Map.class)).thenReturn(Mono.just(resp));

            VoiceAnalysisResult r = service.analyze(new byte[]{1, 2}, "a.webm", "audio/webm");

            assertThat(r.text()).isEqualTo("我不开心");
            assertThat(r.emotion().label()).isEqualTo("悲伤");
            assertThat(r.emotion().labelEn()).isEqualTo("sad");
            assertThat(r.emotion().confidence()).isEqualTo(0.85);
            assertThat(r.emotion().scores()).containsExactly(0.1, 0.2);
            assertThat(r.durationSeconds()).isEqualTo(3.2);
            assertThat(r.hasValidEmotion()).isTrue();
        }

        @Test
        @DisplayName("响应缺 emotion 字段 → 情绪缺省 unknown/置信度 0")
        void missingEmotion_defaults() {
            WebClient deep = injectDeepClient();
            when(deep.post().uri(ANALYZE_URI)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(Map.class)).thenReturn(Mono.just(Map.of("text", "你好")));

            VoiceAnalysisResult r = service.analyze(new byte[]{1}, "a.wav", "audio/wav");

            assertThat(r.text()).isEqualTo("你好");
            assertThat(r.emotion().labelEn()).isEqualTo("unknown");
            assertThat(r.emotion().confidence()).isZero();
            assertThat(r.emotion().scores()).isEmpty();
            assertThat(r.hasValidEmotion()).isFalse();
        }

        @Test
        @DisplayName("emotion map 存在但缺字段 → 各字段走缺省值")
        void emotionMapMissingFields_defaults() {
            WebClient deep = injectDeepClient();
            Map<String, Object> resp = new HashMap<>();
            resp.put("emotion", Map.of());
            when(deep.post().uri(ANALYZE_URI)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(Map.class)).thenReturn(Mono.just(resp));

            VoiceAnalysisResult r = service.analyze(new byte[]{1}, "a.webm", "audio/webm");

            assertThat(r.text()).isEmpty();
            assertThat(r.emotion().label()).isEqualTo("未知");
            assertThat(r.emotion().labelEn()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("响应体为空（Mono.empty）→ fallback")
        void emptyBody_fallback() {
            WebClient deep = injectDeepClient();
            when(deep.post().uri(ANALYZE_URI)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(Map.class)).thenReturn(Mono.empty());

            VoiceAnalysisResult r = service.analyze(new byte[]{1}, "a.webm", "audio/webm");

            assertThat(r.text()).isEmpty();
            assertThat(r.emotion().labelEn()).isEqualTo("unknown");
            assertThat(r.durationSeconds()).isZero();
        }

        @Test
        @DisplayName("Mono 异常 → fallback（不抛出，保护对话主线）")
        void monoError_fallback() {
            WebClient deep = injectDeepClient();
            when(deep.post().uri(ANALYZE_URI)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(Map.class)).thenReturn(Mono.error(new RuntimeException("boom")));

            VoiceAnalysisResult r = service.analyze(new byte[]{1}, "a.webm", "audio/webm");

            assertThat(r.text()).isEmpty();
            assertThat(r.emotion().labelEn()).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("analyze/fetchHealth 服务不可达降级")
    class Unreachable {

        @Test
        @DisplayName("服务不可达 → analyze 返回 fallback（纯文本模式兜底）")
        void analyze_fallback() {
            VoiceAnalysisResult r = service.analyze(new byte[]{1}, "a.webm", "audio/webm");

            assertThat(r.text()).isEmpty();
            assertThat(r.emotion().labelEn()).isEqualTo("unknown");
            assertThat(r.emotion().confidence()).isZero();
        }

        @Test
        @DisplayName("服务不可达 → fetchHealth 返回 null")
        void health_null() {
            assertThat(service.fetchHealth()).isNull();
        }

        @Test
        @DisplayName("服务不可达 → isAvailable 为 false")
        void unavailable() {
            assertThat(service.isAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("fetchHealth/isAvailable 成功路径")
    class HealthSuccess {

        @Test
        @DisplayName("/health 透传返回健康详情（含实际加载模型名）")
        void health_returnsMap() {
            WebClient deep = injectDeepClient();
            Map<String, Object> health = Map.of(
                    "status", "UP", "asr_model", "SenseVoiceSmall", "ser_model", "emotion2vec_plus_large");
            when(deep.get().uri(HEALTH_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.just(health));

            Map<String, Object> result = service.fetchHealth();

            assertThat(result).containsEntry("asr_model", "SenseVoiceSmall");
            assertThat(service.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("/health 异常 → null 且 isAvailable false")
        void healthError_null() {
            WebClient deep = injectDeepClient();
            when(deep.get().uri(HEALTH_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.error(new RuntimeException("down")));

            assertThat(service.fetchHealth()).isNull();
            assertThat(service.isAvailable()).isFalse();
        }
    }
}
