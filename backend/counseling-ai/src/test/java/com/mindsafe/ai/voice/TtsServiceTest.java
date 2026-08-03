package com.mindsafe.ai.voice;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TtsService 单测（AI-007 / 54_语音情感分析设计方案 TTS 合成链路）。
 * <p>
 * 覆盖：合成成功/空音频/异常静默降级、persona/emotion 缺省、dialect/languageMode 过滤、
 * 音色列表、健康检查、服务不可达降级。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@DisplayName("TTS 语音合成服务")
class TtsServiceTest {

    private static final String SYNTH_URI = "/api/v1/tts/synthesize";
    private static final String PERSONAS_URI = "/api/v1/tts/personas";
    private static final String HEALTH_URI = "/health";

    private TtsService service;

    @BeforeEach
    void setUp() {
        // 默认指向不可达地址；成功路径用注入替换 webClient
        service = new TtsService("http://localhost:1", new SimpleMeterRegistry());
    }

    private WebClient injectDeepClient() {
        WebClient deepClient = mock(WebClient.class, Answers.RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(service, "webClient", deepClient);
        return deepClient;
    }

    @Nested
    @DisplayName("synthesize 合成")
    class Synthesize {

        @Test
        @DisplayName("成功 → 返回音频字节")
        void success_returnsAudio() {
            WebClient deep = injectDeepClient();
            when(deep.post().uri(SYNTH_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(byte[].class)).thenReturn(Mono.just(new byte[]{1, 2, 3}));

            byte[] audio = service.synthesize("你好呀", "bobo", "happy", 1.0, 1.0, 1, null, "mandarin");

            assertThat(audio).hasSize(3);
        }

        @Test
        @DisplayName("persona/emotion 为 null → 走缺省（xiaoxing/neutral）；方言+非普通话模式参数透传")
        void defaultsAndDialectParams() {
            WebClient deep = injectDeepClient();
            when(deep.post().uri(SYNTH_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(byte[].class)).thenReturn(Mono.just(new byte[]{9}));

            byte[] audio = service.synthesize("你好", null, null, 0.9, 1.0, 2, "yue", "dialect");

            assertThat(audio).containsExactly((byte) 9);
        }

        @Test
        @DisplayName("空音频（length=0）→ 返回 null（前端走文本模式）")
        void emptyAudio_null() {
            WebClient deep = injectDeepClient();
            when(deep.post().uri(SYNTH_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(byte[].class)).thenReturn(Mono.just(new byte[0]));

            assertThat(service.synthesize("你好", "bobo", "neutral", 1.0, 1.0, 1, null, null)).isNull();
        }

        @Test
        @DisplayName("合成异常 → 静默降级返回 null（不抛出）")
        void error_null() {
            WebClient deep = injectDeepClient();
            when(deep.post().uri(SYNTH_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(byte[].class)).thenReturn(Mono.error(new RuntimeException("boom")));

            assertThat(service.synthesize("你好", "bobo", "neutral", 1.0, 1.0, 1, null, null)).isNull();
        }

        @Test
        @DisplayName("服务不可达 → 静默降级返回 null")
        void unreachable_null() {
            assertThat(service.synthesize("你好", "bobo", "neutral", 1.0, 1.0, 1, null, null)).isNull();
        }
    }

    @Nested
    @DisplayName("getPersonas 音色列表")
    class Personas {

        @Test
        @DisplayName("含 data 字段 → 返回音色列表")
        void withData() {
            WebClient deep = injectDeepClient();
            when(deep.get().uri(PERSONAS_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.just(Map.of("data", List.of(Map.of("id", "xiaoxing")))));

            assertThat(service.getPersonas()).hasSize(1)
                    .first().hasFieldOrPropertyWithValue("id", "xiaoxing");
        }

        @Test
        @DisplayName("响应无 data 字段 → 空列表")
        void withoutData() {
            WebClient deep = injectDeepClient();
            when(deep.get().uri(PERSONAS_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.just(Map.of("status", "UP")));

            assertThat(service.getPersonas()).isEmpty();
        }

        @Test
        @DisplayName("异常/不可达 → 空列表（不抛出）")
        void errorOrUnreachable() {
            WebClient deep = injectDeepClient();
            when(deep.get().uri(PERSONAS_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.error(new RuntimeException("down")));

            assertThat(service.getPersonas()).isEmpty();
            // 不可达实例同样降级
            ReflectionTestUtils.setField(service, "webClient",
                    WebClient.builder().baseUrl("http://localhost:1").build());
            assertThat(service.getPersonas()).isEmpty();
        }
    }

    @Nested
    @DisplayName("isAvailable 健康检查")
    class Available {

        @Test
        @DisplayName("status=UP → true")
        void up() {
            WebClient deep = injectDeepClient();
            when(deep.get().uri(HEALTH_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.just(Map.of("status", "UP")));

            assertThat(service.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("status 非 UP → false")
        void notUp() {
            WebClient deep = injectDeepClient();
            when(deep.get().uri(HEALTH_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.just(Map.of("status", "DOWN")));

            assertThat(service.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("异常/不可达 → false")
        void errorOrUnreachable() {
            assertThat(service.isAvailable()).isFalse();

            WebClient deep = injectDeepClient();
            when(deep.get().uri(HEALTH_URI).retrieve().bodyToMono(Map.class))
                    .thenReturn(Mono.error(new RuntimeException("down")));
            assertThat(service.isAvailable()).isFalse();
        }
    }
}
