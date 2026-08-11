package com.mindsafe.common.dto.chat;

/**
 * SSE 流式消息事件（发送给前端的每一帧）
 * <p>
 * type 取值：
 * - token: 逐字输出
 * - emotion: 情绪识别结果
 * - risk: 风险信号（触发预警）
 * - done: 流结束
 * - error: 错误
 */
public record StreamMessageEvent(
        String type,
        String content,
        MessageMetadata metadata
) {
    public static StreamMessageEvent token(String text) {
        return new StreamMessageEvent("token", text, null);
    }

    public static StreamMessageEvent emotion(String emotionTag, int intensity) {
        return new StreamMessageEvent("emotion", emotionTag, new MessageMetadata(intensity, null, null));
    }

    public static StreamMessageEvent risk(int level, String action) {
        return new StreamMessageEvent("risk", action, new MessageMetadata(null, level, action));
    }

    public static StreamMessageEvent done(String fullText) {
        return new StreamMessageEvent("done", fullText, null);
    }

    public static StreamMessageEvent error(String message) {
        return new StreamMessageEvent("error", message, null);
    }

    public record MessageMetadata(
            Integer emotionIntensity,
            Integer riskLevel,
            String nextAction,
            Boolean fallback
    ) {
        // doing/92 Q-004：兼容旧调用（三参构造 fallback=null）
        public MessageMetadata(Integer emotionIntensity, Integer riskLevel, String nextAction) {
            this(emotionIntensity, riskLevel, nextAction, null);
        }
    }

    /** doing/92 Q-004：降级话术事件（前端按 token 显示，但会话记录/摘要排除——防数据面污染） */
    public static StreamMessageEvent fallback(String text) {
        return new StreamMessageEvent("token", text, new MessageMetadata(null, null, null, true));
    }
}
