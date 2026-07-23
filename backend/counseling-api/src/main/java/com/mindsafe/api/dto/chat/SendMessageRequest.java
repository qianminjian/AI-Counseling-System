package com.mindsafe.api.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 学生发送消息请求
 * <p>
 * M2 扩展：支持语音情绪元数据（前端调用 /voice/analyze 后附带）
 */
public record SendMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 500, message = "消息内容不能超过500字")
        String content,

        /** 语音情绪标签（可选，voice 模式时由前端传入） */
        String voiceEmotion,

        /** 语音情绪置信度 0~1（可选） */
        Double voiceEmotionConfidence,

        /** 消息来源：text / voice（可选，默认 text） */
        String inputMode
) {
    /** 是否为语音输入 */
    public boolean isVoiceInput() {
        return "voice".equals(inputMode);
    }

    /** 是否有有效的语音情绪数据 */
    public boolean hasVoiceEmotion() {
        return voiceEmotion != null
                && voiceEmotionConfidence != null
                && voiceEmotionConfidence > 0.6
                && !"unknown".equals(voiceEmotion)
                && !"other".equals(voiceEmotion);
    }
}
