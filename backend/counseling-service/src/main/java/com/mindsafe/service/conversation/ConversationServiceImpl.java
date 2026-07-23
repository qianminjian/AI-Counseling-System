package com.mindsafe.service.conversation;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话服务实现（M1 核心闭环 + 风险识别 + DB 持久化）
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final AiChatService aiChatService;
    private final RiskDetectorService riskDetectorService;
    private final CounselingSessionMapper sessionMapper;
    private final RiskEventMapper riskEventMapper;
    private final NotificationService notificationService;

    /** 活跃会话内存缓存（emotionTag 等非 DB 字段 + 轮次计数） */
    private final Map<UUID, SessionState> activeSessions = new ConcurrentHashMap<>();

    public ConversationServiceImpl(AiChatService aiChatService,
                                   RiskDetectorService riskDetectorService,
                                   CounselingSessionMapper sessionMapper,
                                   RiskEventMapper riskEventMapper,
                                   NotificationService notificationService) {
        this.aiChatService = aiChatService;
        this.riskDetectorService = riskDetectorService;
        this.sessionMapper = sessionMapper;
        this.riskEventMapper = riskEventMapper;
        this.notificationService = notificationService;
    }

    @Override
    public SessionInfo createSession(UUID tenantId, UUID studentUserId, String emotionTag, String channel) {
        // 1. 持久化会话到 DB
        CounselingSession entity = CounselingSession.create(tenantId, studentUserId, emotionTag, channel);
        sessionMapper.insert(entity);

        UUID sessionId = entity.getSessionId();
        String greeting = buildGreeting(emotionTag);

        // 2. 内存缓存活跃会话状态
        activeSessions.put(sessionId, new SessionState(sessionId, tenantId, studentUserId, emotionTag, channel));
        log.info("会话创建: sessionId={}, student={}, emotion={}", sessionId, studentUserId, emotionTag);

        return new SessionInfo(sessionId, greeting, Instant.now());
    }

    @Override
    public Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID sessionId, String content) {
        return sendMessageStream(tenantId, sessionId, content, null, null);
    }

    @Override
    public Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID sessionId, String content,
                                                      String voiceEmotion, Double voiceEmotionConfidence) {
        SessionState session = activeSessions.get(sessionId);
        if (session == null) {
            return Flux.just(StreamMessageEvent.error("会话不存在"));
        }

        int turn = session.turnCount.incrementAndGet();
        log.debug("收到消息: sessionId={}, turn={}, length={}, voiceEmotion={}",
                sessionId, turn, content.length(), voiceEmotion);

        // 记录语音情绪到会话历史（用于趋势追踪）
        if (voiceEmotion != null && voiceEmotionConfidence != null && voiceEmotionConfidence > 0.6) {
            session.addEmotionRecord(voiceEmotion, voiceEmotionConfidence);
        }

        // 1. 风险检测（文本关键词）
        RiskDetectionResult riskResult = riskDetectorService.detect(content);

        // 2. 多信号融合：文本风险 + 语音情绪
        RiskLevel fusedLevel = fuseRiskSignals(riskResult, voiceEmotion, voiceEmotionConfidence, session);
        boolean isRisky = fusedLevel != null;

        Flux<StreamMessageEvent> riskEvents = Flux.empty();

        if (isRisky) {
            String category = riskResult.isRisky() ? riskResult.category() : "voice_emotion";
            log.warn("风险识别(融合): sessionId={}, level={}, textRisk={}, voiceEmotion={}, consecutiveNegative={}",
                    sessionId, fusedLevel, riskResult.level(), voiceEmotion, session.consecutiveNegativeCount());

            // 持久化风险事件到 DB
            String suggestion = riskResult.isRisky() ? riskResult.suggestion() : buildEmotionSuggestion(voiceEmotion);
            RiskDetectionResult fusedResult = new RiskDetectionResult(
                    fusedLevel, category, riskResult.matchedKeywords(),
                    riskResult.score(), false, suggestion
            );
            persistRiskEvent(session, fusedResult);

            // 更新会话风险快照
            CounselingSession update = new CounselingSession();
            update.setSessionId(sessionId);
            update.setRiskLevelSnapshot(fusedLevel.severity());
            update.setUpdatedAt(Instant.now());
            sessionMapper.updateById(update);

            // 发送风险事件给前端
            riskEvents = Flux.just(
                    StreamMessageEvent.risk(fusedLevel.severity(), fusedResult.suggestion())
            );

            // 红色风险：追加"已通知老师"友好提示
            if (fusedLevel == RiskLevel.RED) {
                riskEvents = riskEvents.concatWith(Flux.just(
                        StreamMessageEvent.token("我很关心你现在的情况。我已经通知了老师，老师会来帮助你。你不是一个人。💙")
                ));
                log.error("🚨 红色风险预警: sessionId={}, student={}, category={}",
                        sessionId, session.studentUserId, category);
            }
        }

        // 3. 调用 AI 服务获取流式回复
        Flux<StreamMessageEvent> aiStream = aiChatService.chat(sessionId, session.emotionTag, content)
                .concatWith(Flux.defer(() -> Flux.just(StreamMessageEvent.done(""))))
                .onErrorResume(e -> {
                    log.error("AI 调用异常: sessionId={}", sessionId, e);
                    return Flux.just(StreamMessageEvent.error("小助手暂时走神了，请再说一次好吗？"));
                });

        // 4. 组合：风险事件 + AI 回复
        return riskEvents.concatWith(aiStream);
    }

    @Override
    public void endSession(UUID tenantId, UUID sessionId) {
        SessionState session = activeSessions.remove(sessionId);
        if (session != null) {
            // 更新 DB 会话状态
            CounselingSession update = new CounselingSession();
            update.setSessionId(sessionId);
            update.setEndedAt(Instant.now());
            update.setSessionStatus("completed");
            update.setUpdatedAt(Instant.now());
            sessionMapper.updateById(update);

            // 清除 AI 对话记忆
            aiChatService.clearMemory(sessionId);
            log.info("会话结束: sessionId={}, turns={}", sessionId, session.turnCount.get());
        }
    }

    private void persistRiskEvent(SessionState session, RiskDetectionResult riskResult) {
        try {
            RiskEvent event = RiskEvent.fromDetection(
                    session.tenantId,
                    session.studentUserId,
                    session.sessionId,
                    riskResult.category(),
                    riskResult.level().severity()
            );
            riskEventMapper.insert(event);
            log.info("风险事件已持久化: riskEventId={}, level={}", event.getRiskEventId(), riskResult.level());

            // 触发教师通知
            notificationService.notifyRiskEvent(event);
        } catch (Exception e) {
            log.error("风险事件持久化失败（不影响对话流）: sessionId={}", session.sessionId, e);
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

    /**
     * 多信号融合风险判断
     * <p>
     * 规则：
     * 1. 文本命中红色关键词 → 直接 RED（不可降级）
     * 2. 文本命中橙色 + 语音消极 → 升级 RED
     * 3. 文本命中橙色（无语音） → ORANGE
     * 4. 文本命中黄色 + 语音消极 → 升级 ORANGE
     * 5. 连续 3 次消极语音情绪（无文本风险） → YELLOW（情绪趋势预警）
     * 6. 单次消极语音（无文本风险） → 不触发风险事件（仅记录）
     */
    private RiskLevel fuseRiskSignals(RiskDetectionResult textRisk, String voiceEmotion,
                                      Double voiceConfidence, SessionState session) {
        boolean hasNegativeVoice = voiceEmotion != null && voiceConfidence != null
                && voiceConfidence > 0.6
                && isNegativeEmotion(voiceEmotion);

        // 规则 1：文本红色不可降级
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.RED) {
            return RiskLevel.RED;
        }

        // 规则 2：文本橙色 + 语音消极 → 升级红色
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.ORANGE && hasNegativeVoice) {
            return RiskLevel.RED;
        }

        // 规则 3：文本橙色（无语音加成）
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.ORANGE) {
            return RiskLevel.ORANGE;
        }

        // 规则 4：文本黄色 + 语音消极 → 升级橙色
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.YELLOW && hasNegativeVoice) {
            return RiskLevel.ORANGE;
        }

        // 规则 4b：文本黄色（无语音加成）
        if (textRisk.isRisky() && textRisk.level() == RiskLevel.YELLOW) {
            return RiskLevel.YELLOW;
        }

        // 规则 5：连续 3 次消极语音（无文本风险）→ 情绪趋势预警
        if (!textRisk.isRisky() && session.consecutiveNegativeCount() >= 3) {
            return RiskLevel.YELLOW;
        }

        // 规则 6：单次消极语音不触发风险事件
        return null;
    }

    private boolean isNegativeEmotion(String emotion) {
        return switch (emotion) {
            case "sad", "fearful", "angry", "disgusted" -> true;
            default -> false;
        };
    }

    private String buildEmotionSuggestion(String voiceEmotion) {
        return switch (voiceEmotion) {
            case "sad" -> "学生语音情绪持续低落，建议关注";
            case "fearful" -> "学生语音中检测到恐惧情绪，建议关注";
            case "angry" -> "学生语音情绪激动，建议关注";
            default -> "学生语音情绪异常，建议关注";
        };
    }

    /** 内存会话状态（含情绪趋势追踪） */
    private static class SessionState {
        final UUID sessionId;
        final UUID tenantId;
        final UUID studentUserId;
        final String emotionTag;
        final String channel;
        final AtomicInteger turnCount = new AtomicInteger(0);

        /** 语音情绪历史（最近 10 条） */
        private final List<EmotionRecord> emotionHistory = new ArrayList<>();

        SessionState(UUID sessionId, UUID tenantId, UUID studentUserId, String emotionTag, String channel) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.studentUserId = studentUserId;
            this.emotionTag = emotionTag;
            this.channel = channel;
        }

        void addEmotionRecord(String emotion, double confidence) {
            emotionHistory.add(new EmotionRecord(emotion, confidence, Instant.now()));
            // 只保留最近 10 条
            if (emotionHistory.size() > 10) {
                emotionHistory.remove(0);
            }
        }

        /** 连续消极情绪计数（从最近一条往前数） */
        int consecutiveNegativeCount() {
            int count = 0;
            for (int i = emotionHistory.size() - 1; i >= 0; i--) {
                String e = emotionHistory.get(i).emotion();
                if ("sad".equals(e) || "fearful".equals(e) || "angry".equals(e) || "disgusted".equals(e)) {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        }

        record EmotionRecord(String emotion, double confidence, Instant timestamp) {}
    }
}
