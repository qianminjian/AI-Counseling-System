package com.mindsafe.api.controller;

import com.mindsafe.common.exception.BizException;
import com.mindsafe.ai.voice.VoiceAnalysisResult;
import com.mindsafe.ai.voice.VoiceAnalysisService;
import com.mindsafe.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VoiceController 单元测试（P1 覆盖率冲刺：语音分析/服务状态）
 * <p>
 * isNegativeEmotion/emotionRiskLevel 为 record 真实方法：构造高置信度情绪验证。
 */
class VoiceControllerTest {

    private VoiceAnalysisService voiceAnalysisService;
    private VoiceController controller;

    @BeforeEach
    void setUp() {
        voiceAnalysisService = mock(VoiceAnalysisService.class);
        controller = new VoiceController(voiceAnalysisService);
    }

    private VoiceAnalysisResult result(String labelEn, double confidence) {
        return new VoiceAnalysisResult("我想哭",
                new VoiceAnalysisResult.EmotionInfo("悲伤", labelEn, confidence,
                        List.of(0.1, 0.1, 0.1, 0.6, 0.1, 0.0, 0.0, 0.0, 0.0)),
                3.5);
    }

    @Test
    @DisplayName("analyzeVoice 空文件 → 400")
    void analyze_emptyFile() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        assertThatThrownBy(() -> controller.analyzeVoice(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("音频文件不能为空");
    }

    @Test
    @DisplayName("analyzeVoice 非音频 content-type → 400")
    void analyze_wrongContentType() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("text/plain");

        assertThatThrownBy(() -> controller.analyzeVoice(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持音频文件");
    }

    @Test
    @DisplayName("analyzeVoice content-type 为 null → 400")
    void analyze_nullContentType() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn(null);

        assertThatThrownBy(() -> controller.analyzeVoice(file))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("BUG-VOICE-01: analyzeVoice 超 10MB → 400 明确拒绝（原转发致 voice-service 400 被包装为 500）")
    void analyze_oversizeFile() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("audio/mp3");
        when(file.getSize()).thenReturn(10L * 1024 * 1024 + 1);

        assertThatThrownBy(() -> controller.analyzeVoice(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("10MB");
        verify(voiceAnalysisService, never()).analyze(any(), any(), any());
    }

    @Test
    @DisplayName("analyzeVoice 成功 → 转写+情绪映射（非消极）")
    void analyze_success_neutral() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("audio/webm");
        when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});
        when(file.getOriginalFilename()).thenReturn("rec.webm");
        VoiceAnalysisResult result = result("neutral", 0.9);
        when(voiceAnalysisService.analyze(new byte[]{1, 2, 3}, "rec.webm", "audio/webm")).thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.analyzeVoice(file);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("text")).isEqualTo("我想哭");
        assertThat(resp.data().get("durationSeconds")).isEqualTo(3.5);
        assertThat(resp.data().get("isNegative")).isEqualTo(false);
        assertThat(resp.data().get("emotionRiskLevel")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> emotion = (Map<String, Object>) resp.data().get("emotion");
        assertThat(emotion.get("label")).isEqualTo("悲伤");
        assertThat(emotion.get("labelEn")).isEqualTo("neutral");
        assertThat(emotion.get("confidence")).isEqualTo(0.9);
    }

    @Test
    @DisplayName("analyzeVoice 消极情绪 sad → isNegative=true + riskLevel=2")
    void analyze_success_sad() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("audio/webm");
        when(file.getBytes()).thenReturn(new byte[]{1});
        when(file.getOriginalFilename()).thenReturn("rec.webm");
        VoiceAnalysisResult result = result("sad", 0.9);
        when(voiceAnalysisService.analyze(new byte[]{1}, "rec.webm", "audio/webm")).thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.analyzeVoice(file);

        assertThat(resp.data().get("isNegative")).isEqualTo(true);
        assertThat(resp.data().get("emotionRiskLevel")).isEqualTo(2);
    }

    @Test
    @DisplayName("analyzeVoice 文件名缺失 → 默认 audio.webm")
    void analyze_noFilename() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("audio/webm");
        when(file.getBytes()).thenReturn(new byte[]{1});
        when(file.getOriginalFilename()).thenReturn(null);
        VoiceAnalysisResult result = result("neutral", 0.9);
        when(voiceAnalysisService.analyze(new byte[]{1}, "audio.webm", "audio/webm")).thenReturn(result);

        ApiResponse<Map<String, Object>> resp = controller.analyzeVoice(file);

        assertThat(resp.code()).isEqualTo(0);
        verify(voiceAnalysisService).analyze(new byte[]{1}, "audio.webm", "audio/webm");
    }

    @Test
    @DisplayName("status 服务不可用 → available=false")
    void status_unavailable() {
        when(voiceAnalysisService.fetchHealth()).thenReturn(null);

        ApiResponse<Map<String, Object>> resp = controller.status();

        assertThat(resp.data().get("available")).isEqualTo(false);
        assertThat(resp.data().get("models")).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("status 服务正常 → 模型名拼接")
    void status_available() {
        when(voiceAnalysisService.fetchHealth()).thenReturn(Map.of("asr_model", "whisper", "ser_model", "emotion2"));

        ApiResponse<Map<String, Object>> resp = controller.status();

        assertThat(resp.data().get("available")).isEqualTo(true);
        assertThat(resp.data().get("models")).isEqualTo("whisper + emotion2");
    }

    @Test
    @DisplayName("status 健康检查缺模型字段 → unknown 兜底")
    void status_missingModelFields() {
        when(voiceAnalysisService.fetchHealth()).thenReturn(Map.of());

        ApiResponse<Map<String, Object>> resp = controller.status();

        assertThat(resp.data().get("available")).isEqualTo(true);
        assertThat(resp.data().get("models")).isEqualTo("unknown + unknown");
    }
}
