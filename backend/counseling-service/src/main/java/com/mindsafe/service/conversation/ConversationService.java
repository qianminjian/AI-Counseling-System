package com.mindsafe.service.conversation;

import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.domain.entity.CounselingSession;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 对话服务接口（M1 核心链路 + M2 语音情绪融合）
 */
public interface ConversationService {

    /**
     * 创建辅导会话
     */
    SessionInfo createSession(UUID tenantId, UUID studentUserId, String emotionTag, String channel);

    /**
     * 发送消息并获取 AI 流式回复（纯文本）
     *
     * @param studentUserId 调用方身份（会话归属校验，防跨会话劫持）
     */
    Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID studentUserId, UUID sessionId, String content);

    /**
     * 发送消息并获取 AI 流式回复（含语音情绪上下文）
     *
     * @param studentUserId          调用方身份（会话归属校验，防跨会话劫持）
     * @param voiceEmotion           语音情绪标签（sad/fearful/angry 等）
     * @param voiceEmotionConfidence 置信度 0~1
     */
    Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID studentUserId, UUID sessionId, String content,
                                               String voiceEmotion, Double voiceEmotionConfidence);

    /**
     * 更新前端客户端设置状态（TTS静音/唤醒开关），让 AI 知道自己的能力边界
     *
     * @param studentUserId 调用方身份（会话归属校验）
     */
    void updateClientSettings(UUID tenantId, UUID studentUserId, UUID sessionId, Boolean ttsMuted, Boolean wakeEnabled);

    /**
     * 结束会话
     *
     * @param studentUserId 调用方身份（会话归属校验，非持有人拒绝）
     */
    void endSession(UUID tenantId, UUID studentUserId, UUID sessionId);

    /**
     * 冷场暖场（nudge，design/28 §三 3.4）
     * <p>
     * 前端沉默检测满足后调用；后端冷场决策模型计算留白/暖场：
     * 留白（warmthLevel=0）返回空 Flux（不打扰，把安静还给孩子）；
     * 暖场（warmthLevel≥1）返回与 messages 相同的 SSE token 流。
     *
     * @param silenceSeconds 前端上报的沉默时长（秒）
     * @param studentUserId  调用方身份（会话归属校验）
     */
    Flux<StreamMessageEvent> sendNudgeStream(UUID tenantId, UUID studentUserId, UUID sessionId, int silenceSeconds);

    /**
     * 会话历史列表（T4 批次C 下沉：租户+学生双重条件内置，分页上限 50）。
     */
    List<CounselingSession> getSessionHistory(UUID tenantId, UUID studentUserId, int limit);

    /**
     * 满意度评价（T4 批次B 下沉：先归属校验再更新，非持有人拒绝）。
     */
    void rateSession(UUID tenantId, UUID studentUserId, UUID sessionId, int rating, String comment);
}
