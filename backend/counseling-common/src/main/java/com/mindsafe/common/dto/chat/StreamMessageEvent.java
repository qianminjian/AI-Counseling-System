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

    /**
     * AUTH-030：每日使用时长已达上限（前端展示专门休息引导页并禁用输入，
     * content 为预审核引导文案；与 token 事件区分，避免被当作普通 AI 回复播报）
     */
    public static StreamMessageEvent usageLimit(String guidance) {
        return new StreamMessageEvent("usage_limit", guidance, null);
    }

    /** AUTH-030：距上限不足预警（content 为含剩余分钟数的提示文案，前端顶部横幅提示一次） */
    public static StreamMessageEvent usageWarning(String text) {
        return new StreamMessageEvent("usage_warning", text, null);
    }
}
