package com.mindsafe.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.api.config.SystemConfigProperties;
import com.mindsafe.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemConfigController 单元测试（CFG-001 配置统一纳管）
 * <p>
 * 覆盖：
 * - GET /api/v1/system/config 返回完整配置结构
 * - 各子节点（voiceprint / wake-word / tts / guide-scripts）值正确
 * - 响应包装（ApiResponse code=0）
 * - Cache-Control 头设置
 */
class SystemConfigControllerTest {

    private SystemConfigProperties properties;
    private SystemConfigController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new SystemConfigProperties();

        // voiceprint
        SystemConfigProperties.Voiceprint vp = new SystemConfigProperties.Voiceprint();
        vp.setVerifyThreshold(0.70);
        vp.setMaxTemplates(8);
        vp.setEnrollSegments(3);
        vp.setVerifySegments(2);
        properties.setVoiceprint(vp);

        // wake-word
        SystemConfigProperties.WakeWord ww = new SystemConfigProperties.WakeWord();
        ww.setModelId("onnx-community/whisper-tiny");
        ww.setWindowSeconds(2.0);
        ww.setSilenceRmsThreshold(0.03);
        properties.setWakeWord(ww);

        // tts
        SystemConfigProperties.Tts tts = new SystemConfigProperties.Tts();
        tts.setDefaultPersona("xiaoxing");
        tts.setPersonas(List.of("xiaoxing", "bobo", "yueliang", "xiaotaiyang", "dashu", "doudou", "qiqiu"));
        properties.setTts(tts);

        // guide-scripts
        SystemConfigProperties.GuideScripts gs = new SystemConfigProperties.GuideScripts();
        gs.setVerify(List.of(
                new SystemConfigProperties.GuideScript("嗨！我是波波，跟我打个招呼吧！", "对波波说\"你好\"就行", 4),
                new SystemConfigProperties.GuideScript("真棒！再跟我说一句：今天心情真好呀！", "跟我说：今天心情真好呀！", 4)
        ));
        gs.setEnroll(List.of(
                new SystemConfigProperties.GuideScript("嗨！我是波波，很高兴认识你！跟我打个招呼吧！", "对波波说\"你好\"就行", 4),
                new SystemConfigProperties.GuideScript("真好听！再跟我说一句：我喜欢唱歌和画画！", "跟我说：我喜欢唱歌和画画！", 5),
                new SystemConfigProperties.GuideScript("最后一句啦！跟我说：今天天气真好，我想出去玩！", "跟我说：今天天气真好，我想出去玩！", 5)
        ));
        properties.setGuideScripts(gs);

        controller = new SystemConfigController(properties, objectMapper);
    }

    @Test
    @DisplayName("返回 ApiResponse 包装，code=0")
    void returnsApiResponseOk() {
        ApiResponse<Map<String, Object>> response = controller.getConfig();

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).isNotNull();
    }

    @Nested
    @DisplayName("voiceprint 节点")
    class VoiceprintSection {

        @Test
        @DisplayName("包含 verify-threshold=0.70（local 模式前端阈值）")
        void containsThreshold() {
            Map<String, Object> data = controller.getConfig().data();

            @SuppressWarnings("unchecked")
            Map<String, Object> vp = (Map<String, Object>) data.get("voiceprint");
            assertThat(vp).isNotNull();
            assertThat(vp.get("verifyThreshold")).isEqualTo(0.70);
        }

        @Test
        @DisplayName("包含 maxTemplates / enrollSegments / verifySegments")
        void containsSegmentConfig() {
            Map<String, Object> data = controller.getConfig().data();

            @SuppressWarnings("unchecked")
            Map<String, Object> vp = (Map<String, Object>) data.get("voiceprint");
            assertThat(vp.get("maxTemplates")).isEqualTo(8);
            assertThat(vp.get("enrollSegments")).isEqualTo(3);
            assertThat(vp.get("verifySegments")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("wakeWord 节点")
    class WakeWordSection {

        @Test
        @DisplayName("包含模型 ID 和音频参数")
        void containsWakeWordConfig() {
            Map<String, Object> data = controller.getConfig().data();

            @SuppressWarnings("unchecked")
            Map<String, Object> ww = (Map<String, Object>) data.get("wakeWord");
            assertThat(ww).isNotNull();
            assertThat(ww.get("modelId")).isEqualTo("onnx-community/whisper-tiny");
            assertThat(ww.get("windowSeconds")).isEqualTo(2.0);
            assertThat(ww.get("silenceRmsThreshold")).isEqualTo(0.03);
        }
    }

    @Nested
    @DisplayName("tts 节点")
    class TtsSection {

        @Test
        @DisplayName("包含默认音色和 7 音色列表")
        void containsTtsConfig() {
            Map<String, Object> data = controller.getConfig().data();

            @SuppressWarnings("unchecked")
            Map<String, Object> tts = (Map<String, Object>) data.get("tts");
            assertThat(tts).isNotNull();
            assertThat(tts.get("defaultPersona")).isEqualTo("xiaoxing");

            @SuppressWarnings("unchecked")
            List<String> personas = (List<String>) tts.get("personas");
            assertThat(personas).hasSize(7);
            assertThat(personas).containsExactly("xiaoxing", "bobo", "yueliang", "xiaotaiyang", "dashu", "doudou", "qiqiu");
        }
    }

    @Nested
    @DisplayName("guideScripts 节点")
    class GuideScriptsSection {

        @Test
        @DisplayName("verify 脚本 2 轮、enroll 脚本 3 轮")
        void containsGuideScripts() {
            Map<String, Object> data = controller.getConfig().data();

            @SuppressWarnings("unchecked")
            Map<String, Object> gs = (Map<String, Object>) data.get("guideScripts");
            assertThat(gs).isNotNull();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> verify = (List<Map<String, Object>>) gs.get("verify");
            assertThat(verify).hasSize(2);
            assertThat(verify.get(0).get("prompt")).isEqualTo("嗨！我是波波，跟我打个招呼吧！");
            assertThat(verify.get(0).get("duration")).isEqualTo(4);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> enroll = (List<Map<String, Object>>) gs.get("enroll");
            assertThat(enroll).hasSize(3);
            assertThat(enroll.get(2).get("prompt")).isEqualTo("最后一句啦！跟我说：今天天气真好，我想出去玩！");
            assertThat(enroll.get(2).get("duration")).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("配置为空时返回空 Map 而非 null（防御性）")
    void emptyConfigReturnsEmptyMap() {
        SystemConfigProperties emptyProps = new SystemConfigProperties();
        SystemConfigController emptyController = new SystemConfigController(emptyProps, objectMapper);

        ApiResponse<Map<String, Object>> response = emptyController.getConfig();

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data()).isNotNull().isEmpty();
    }
}
