package com.mindsafe.service.conversation;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话服务实现（M1 最小闭环 + 风险识别）
 * <p>
 * M1 阶段简化：会话状态存内存，不持久化（后续迭代加 DB）。
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final AiChatService aiChatService;
    private final RiskDetectorService riskDetectorService;

    /** M1 简化：内存会话存储（后续替换为 DB + Redis） */
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();

    public ConversationServiceImpl(AiChatService aiChatService, RiskDetectorService riskDetectorService) {
        this.aiChatService = aiChatService;
        this.riskDetectorService = riskDetectorService;
    }

    @Override
    public SessionInfo createSession(UUID tenantId, UUID studentUserId, String emotionTag, String channel) {
        UUID sessionId = UUID.randomUUID();
        String greeting = buildGreeting(emotionTag);

        sessions.put(sessionId, new SessionState(sessionId, tenantId, studentUserId, emotionTag, channel));
        log.info("会话创建: sessionId={}, student={}, emotion={}", sessionId, studentUserId, emotionTag);

        return new SessionInfo(sessionId, greeting, Instant.now());
    }

    @Override
    public Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID sessionId, String content) {
        SessionState session = sessions.get(sessionId);
        if (session == null) {
            return Flux.just(StreamMessageEvent.error("会话不存在"));
        }

        int turn = session.turnCount.incrementAndGet();
        log.debug("收到消息: sessionId={}, turn={}, length={}", sessionId, turn, content.length());

        // 1. 风险检测（先于 AI 调用）
        RiskDetectionResult riskResult = riskDetectorService.detect(content);
        Flux<StreamMessageEvent> riskEvents = Flux.empty();

        if (riskResult.isRisky()) {
            log.warn("风险识别: sessionId={}, level={}, category={}, keywords={}",
                    sessionId, riskResult.level(), riskResult.category(), riskResult.matchedKeywords());

            // 发送风险事件给前端
            riskEvents = Flux.just(
                    StreamMessageEvent.risk(riskResult.level().severity(), riskResult.suggestion())
            );

            // 红色风险：追加“已通知老师”友好提示
            if (riskResult.level() == RiskLevel.RED) {
                riskEvents = riskEvents.concatWith(Flux.just(
                        StreamMessageEvent.token("我很关心你现在的情况。我已经通知了老师，老师会来帮助你。你不是一个人。💙")
                ));
                // TODO M2: 实际发送通知给教师端（站内消息 + 短信）
                log.error("🚨 红色风险预警: sessionId={}, student={}, category={}",
                        sessionId, session.studentUserId, riskResult.category());
            }
        }

        // 2. 调用 AI 服务获取流式回复
        Flux<StreamMessageEvent> aiStream = aiChatService.chat(sessionId, session.emotionTag, content)
                .concatWith(Flux.defer(() -> Flux.just(StreamMessageEvent.done(""))))
                .onErrorResume(e -> {
                    log.error("AI 调用异常: sessionId={}", sessionId, e);
                    return Flux.just(StreamMessageEvent.error("小助手暂时走神了，请再说一次好吗？"));
                });

        // 3. 组合：风险事件 + AI 回复
        return riskEvents.concatWith(aiStream);
    }

    @Override
    public void endSession(UUID tenantId, UUID sessionId) {
        SessionState session = sessions.remove(sessionId);
        if (session != null) {
            // 清除 AI 对话记忆
            aiChatService.clearMemory(sessionId);
            log.info("会话结束: sessionId={}, turns={}", sessionId, session.turnCount.get());
        }
    }

    private String buildGreeting(String emotionTag) {
        return switch (emotionTag) {
            case "happy" -> "嗨！看起来你今天心情不错呀！想和我聊聊什么开心的事吗？😊";
            case "sad" -> "嗨，我感觉到你今天有点难过。没关系，我在这里陪着你，想和我说说吗？💙";
            case "angry" -> "嗨，看起来你现在有些生气。生气是很正常的感受哦，想和我聊聊发生了什么吗？";
            case "scared" -> "嗨，我感觉到你有些害怕。别担心，这里很安全，我会一直陪着你。🌟";
            case "nervous" -> "嗨，看起来你有点紧张。深呼吸一下，我们慢慢聊，不着急。🌈";
            default -> "嗨！我是你的心理小伙伴，今天想和我聊些什么呢？";
        };
    }

    /** 内存会话状态（M1 简化，可变计数器） */
    private static class SessionState {
        final UUID sessionId;
        final UUID tenantId;
        final UUID studentUserId;
        final String emotionTag;
        final String channel;
        final AtomicInteger turnCount = new AtomicInteger(0);

        SessionState(UUID sessionId, UUID tenantId, UUID studentUserId, String emotionTag, String channel) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.studentUserId = studentUserId;
            this.emotionTag = emotionTag;
            this.channel = channel;
        }
    }
}
