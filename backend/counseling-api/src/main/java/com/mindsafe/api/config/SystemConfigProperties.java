package com.mindsafe.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 前端运行时配置属性（CFG-001 配置统一纳管）
 * <p>
 * 绑定 application.yml 中 mindsafe.system-config 子树，
 * 通过 GET /api/v1/system/config 下发前端，覆盖本地默认值。
 * <p>
 * 设计原则：
 * - 此处仅放"前端需要知道"的业务参数，密钥/服务 URL 等不下发
 * - 声纹阈值 0.70 单一事实源（AUD-001：local/remote 同阈值，原 remote 0.55 已废弃）
 * - 引导脚本可运营调整，无需前端发版
 */
@Component
@ConfigurationProperties(prefix = "mindsafe.system-config")
public class SystemConfigProperties {

    private Voiceprint voiceprint;
    private WakeWord wakeWord;
    private Tts tts;
    private GuideScripts guideScripts;

    public Voiceprint getVoiceprint() {
        return voiceprint;
    }

    public void setVoiceprint(Voiceprint voiceprint) {
        this.voiceprint = voiceprint;
    }

    public WakeWord getWakeWord() {
        return wakeWord;
    }

    public void setWakeWord(WakeWord wakeWord) {
        this.wakeWord = wakeWord;
    }

    public Tts getTts() {
        return tts;
    }

    public void setTts(Tts tts) {
        this.tts = tts;
    }

    public GuideScripts getGuideScripts() {
        return guideScripts;
    }

    public void setGuideScripts(GuideScripts guideScripts) {
        this.guideScripts = guideScripts;
    }

    /**
     * 声纹识别前端配置（local 模式参数）
     */
    public static class Voiceprint {
        /** 前端 local 模式余弦相似度阈值（本地 embedding 质量稳定，可用较严阈值） */
        private double verifyThreshold = 0.70;
        /** 每用户最大模板数 */
        private int maxTemplates = 8;
        /** 注册时采集段数 */
        private int enrollSegments = 3;
        /** 验证时采集段数 */
        private int verifySegments = 2;

        public double getVerifyThreshold() {
            return verifyThreshold;
        }

        public void setVerifyThreshold(double verifyThreshold) {
            this.verifyThreshold = verifyThreshold;
        }

        public int getMaxTemplates() {
            return maxTemplates;
        }

        public void setMaxTemplates(int maxTemplates) {
            this.maxTemplates = maxTemplates;
        }

        public int getEnrollSegments() {
            return enrollSegments;
        }

        public void setEnrollSegments(int enrollSegments) {
            this.enrollSegments = enrollSegments;
        }

        public int getVerifySegments() {
            return verifySegments;
        }

        public void setVerifySegments(int verifySegments) {
            this.verifySegments = verifySegments;
        }
    }

    /**
     * 语音唤醒配置
     */
    public static class WakeWord {
        /** Whisper 模型 ID */
        private String modelId = "onnx-community/whisper-tiny";
        /** 滑窗长度（秒） */
        private double windowSeconds = 2.0;
        /** 静音 RMS 阈值 */
        private double silenceRmsThreshold = 0.03;

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId;
        }

        public double getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(double windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public double getSilenceRmsThreshold() {
            return silenceRmsThreshold;
        }

        public void setSilenceRmsThreshold(double silenceRmsThreshold) {
            this.silenceRmsThreshold = silenceRmsThreshold;
        }
    }

    /**
     * TTS 语音合成前端配置
     */
    public static class Tts {
        /** 默认音色人设 */
        private String defaultPersona = "xiaoxing";
        /** 可用音色列表 */
        private List<String> personas = new ArrayList<>();

        public String getDefaultPersona() {
            return defaultPersona;
        }

        public void setDefaultPersona(String defaultPersona) {
            this.defaultPersona = defaultPersona;
        }

        public List<String> getPersonas() {
            return personas;
        }

        public void setPersonas(List<String> personas) {
            this.personas = personas;
        }
    }

    /**
     * 声纹引导对话脚本（运营可调，无需前端发版）
     */
    public static class GuideScripts {
        /** 验证模式脚本（2 轮） */
        private List<GuideScript> verify = new ArrayList<>();
        /** 注册模式脚本（3 轮） */
        private List<GuideScript> enroll = new ArrayList<>();

        public List<GuideScript> getVerify() {
            return verify;
        }

        public void setVerify(List<GuideScript> verify) {
            this.verify = verify;
        }

        public List<GuideScript> getEnroll() {
            return enroll;
        }

        public void setEnroll(List<GuideScript> enroll) {
            this.enroll = enroll;
        }
    }

    /**
     * 单条引导脚本
     */
    public record GuideScript(
            /** 波波 TTS 提问文本 */
            String prompt,
            /** 界面提示文字 */
            String hint,
            /** 录音窗口时长（秒） */
            int duration
    ) {
    }
}
