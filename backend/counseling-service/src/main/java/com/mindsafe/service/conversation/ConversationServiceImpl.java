package com.mindsafe.service.conversation;

import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.ai.safety.PiiDesensitizer;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.profile.StudentProfileService;
import com.mindsafe.service.usage.UsageTimeLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
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
    private final PiiDesensitizer piiDesensitizer;
    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final RiskEventMapper riskEventMapper;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final StudentProfileService profileService;
    private final ProfileExtractorService profileExtractorService;
    private final UsageTimeLimitService usageTimeLimitService;

    /** 活跃会话内存缓存（emotionTag 等非 DB 字段 + 轮次计数） */
    private final Map<UUID, SessionState> activeSessions = new ConcurrentHashMap<>();

    public ConversationServiceImpl(AiChatService aiChatService,
                                   RiskDetectorService riskDetectorService,
                                   PiiDesensitizer piiDesensitizer,
                                   CounselingSessionMapper sessionMapper,
                                   MessageSummaryMapper messageSummaryMapper,
                                   RiskEventMapper riskEventMapper,
                                   NotificationService notificationService,
                                   UserMapper userMapper,
                                   StudentProfileService profileService,
                                   ProfileExtractorService profileExtractorService,
                                   UsageTimeLimitService usageTimeLimitService) {
        this.aiChatService = aiChatService;
        this.riskDetectorService = riskDetectorService;
        this.piiDesensitizer = piiDesensitizer;
        this.sessionMapper = sessionMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.riskEventMapper = riskEventMapper;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.profileService = profileService;
        this.profileExtractorService = profileExtractorService;
        this.usageTimeLimitService = usageTimeLimitService;
    }

    @Override
    public SessionInfo createSession(UUID tenantId, UUID studentUserId, String emotionTag, String channel) {
        // 1. 持久化会话到 DB
        CounselingSession entity = CounselingSession.create(tenantId, studentUserId, emotionTag, channel);
        sessionMapper.insert(entity);

        UUID sessionId = entity.getSessionId();
        String greeting = buildGreeting(emotionTag);

        // 2. 内存缓存活跃会话状态（查询用户性别用于 Prompt 个性化）
        User user = userMapper.selectById(studentUserId);
        String gender = (user != null) ? user.getGender() : null;
        activeSessions.put(sessionId, new SessionState(sessionId, tenantId, studentUserId, emotionTag, channel, gender));
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

        // AUTH-030：累计每日使用时长（按距上次消息的间隔，上限 5 分钟）
        long elapsedSec = session.markActiveAndElapsed();
        if (elapsedSec > 0) {
            usageTimeLimitService.addUsage(session.tenantId, session.studentUserId, elapsedSec);
        }

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

            // 红色风隩：追加“已通知老师”友好提示 + 会话升级为 escalated
            if (fusedLevel == RiskLevel.RED) {
                riskEvents = riskEvents.concatWith(Flux.just(
                        StreamMessageEvent.token("我很关心你现在的情况。我已经通知了老师，老师会来帮助你。你不是一个人。💙")
                ));
                // 会话状态升级为 escalated
                CounselingSession escalate = new CounselingSession();
                escalate.setSessionId(sessionId);
                escalate.setSessionStatus("escalated");
                escalate.setUpdatedAt(Instant.now());
                sessionMapper.updateById(escalate);
            
                log.error("🚨 红色风隩预警(已升级): sessionId={}, student={}, category={}",
                        sessionId, session.studentUserId, category);
            }
        }

        // 3. PII 服务端脱敏：风险检测已用原文完成（需捕获"地址+自伤"等组合），
        //    脱敏后的内容才进入 LLM / 对话记忆，确保原始 PII 不被 AI 复述或残留
        String safeContent = piiDesensitizer.desensitize(content);
        if (!safeContent.equals(content)) {
            log.info("PII 已脱敏: sessionId={}", sessionId);
        }

        // 4. 持久化学生消息摘要（异步，不阻塞主流程）
        int riskLevelValue = fusedLevel != null ? fusedLevel.severity() : 0;
        persistStudentMessageSummary(session, turn, content, session.emotionTag, riskLevelValue);

        // 4.5 AUTH-030：每日使用时长超限 → 引导休息（红色风险优先，不拦截）
        if (fusedLevel != RiskLevel.RED
                && usageTimeLimitService.isExceeded(session.tenantId, session.studentUserId)) {
            log.info("每日使用时长已达上限，引导休息: sessionId={}, student={}, usedSec={}",
                    sessionId, session.studentUserId,
                    usageTimeLimitService.getUsedSeconds(session.tenantId, session.studentUserId));
            String guidance = "今天我们聊了不少啦，你已经很棒了。为了让眼睛和心情都休息一下，今天就先到这里好吗？"
                    + "明天我还在这里等你。\uD83C\uDF19 如果现在有紧急的事情，可以告诉老师，或拨打心理援助热线 12355。";
            return riskEvents.concatWith(Flux.just(
                    StreamMessageEvent.token(guidance),
                    StreamMessageEvent.done("")
            ));
        }

        // 5. 调用 AI 服务获取流式回复（注入学生画像）
        String profilePrompt = profileService.buildProfilePrompt(session.tenantId, session.studentUserId);
        StringBuilder aiResponseCollector = new StringBuilder();
        Flux<StreamMessageEvent> aiStream = aiChatService.chat(sessionId, session.emotionTag, safeContent, session.gender, profilePrompt)
                .doOnNext(event -> {
                    if (event.type() != null && event.type().equals("token") && event.content() != null) {
                        aiResponseCollector.append(event.content());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    // AI 回复完成后持久化摘要
                    persistAiMessageSummary(session, turn, aiResponseCollector.toString());
                    return Flux.just(StreamMessageEvent.done(""));
                }))
                .onErrorResume(e -> {
                    log.error("AI 调用异常: sessionId={}", sessionId, e);
                    return Flux.just(StreamMessageEvent.error("小助手暂时走神了，请再说一次好吗？"));
                });

        // 6. 组合：风险事件 + AI 回复
        return riskEvents.concatWith(aiStream);
    }

    @Override
    public void endSession(UUID tenantId, UUID sessionId) {
        SessionState session = activeSessions.remove(sessionId);
        if (session != null) {
            // 更新 DB 会话状态 + 轮次数
            CounselingSession update = new CounselingSession();
            update.setSessionId(sessionId);
            update.setEndedAt(Instant.now());
            update.setSessionStatus("completed");
            update.setTurnCount(session.turnCount.get());
            update.setUpdatedAt(Instant.now());
            sessionMapper.updateById(update);

            // 清除 AI 对话记忆
            aiChatService.clearMemory(sessionId);
            log.info("会话结束: sessionId={}, turns={}", sessionId, session.turnCount.get());

            // 异步生成 AI 会话摘要（摘要完成后触发 PROF-003 画像 LLM 提炼）
            generateSummaryAsync(tenantId, sessionId, session.studentUserId);

            // 异步更新学生画像（基于历史会话统计）
            profileService.updateProfile(session.tenantId, session.studentUserId);
        }
    }

    /** 异步生成会话摘要（不阻塞主流程），摘要完成后触发画像 LLM 提炼（PROF-003） */
    @Async
    public void generateSummaryAsync(UUID tenantId, UUID sessionId, UUID studentUserId) {
        try {
            // 1. 查询该会话所有消息摘要
            List<MessageSummary> messages = messageSummaryMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageSummary>()
                            .eq(MessageSummary::getTenantId, tenantId)
                            .eq(MessageSummary::getSessionId, sessionId)
                            .orderByAsc(MessageSummary::getTurnCount)
                            .orderByAsc(MessageSummary::getCreatedAt)
            );
            if (messages.isEmpty()) return;

            // 2. 拼接对话文本
            StringBuilder sb = new StringBuilder();
            for (MessageSummary m : messages) {
                String role = "student".equals(m.getSenderType()) ? "学生" : "AI";
                sb.append(role).append(": ").append(m.getContentSummary()).append("\n");
            }
            String conversationText = sb.toString();

            // 3. 调用 LLM 生成摘要
            String summary = aiChatService.generateSessionSummary(conversationText);
            if (summary != null && !summary.isBlank()) {
                CounselingSession update = new CounselingSession();
                update.setSessionId(sessionId);
                update.setSessionSummary(summary);
                update.setUpdatedAt(Instant.now());
                sessionMapper.updateById(update);
                log.info("会话摘要已生成: sessionId={}", sessionId);

                // 4. PROF-003：基于摘要 + 对话文本提炼画像增量（沟通偏好/韧性/社交图谱）
                profileExtractorService.extractAndMerge(tenantId, studentUserId, conversationText, summary);
            }
        } catch (Exception e) {
            log.warn("会话摘要生成失败（不影响业务）: sessionId={}", sessionId, e);
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

    /** 持久化学生消息摘要（fire-and-forget，不影响主流程） */
    private void persistStudentMessageSummary(SessionState session, int turn,
                                              String content, String emotionLabel, int riskLevel) {
        try {
            MessageSummary summary = MessageSummary.studentMessage(
                    session.tenantId, session.sessionId, session.studentUserId,
                    turn, content, emotionLabel, riskLevel
            );
            messageSummaryMapper.insert(summary);
        } catch (Exception e) {
            log.warn("学生消息摘要持久化失败（不影响对话）: sessionId={}, turn={}", session.sessionId, turn, e);
        }
    }

    /** 持久化 AI 回复摘要 */
    private void persistAiMessageSummary(SessionState session, int turn, String aiResponse) {
        try {
            if (aiResponse == null || aiResponse.isBlank()) return;
            MessageSummary summary = MessageSummary.aiMessage(
                    session.tenantId, session.sessionId, session.studentUserId,
                    turn, aiResponse
            );
            messageSummaryMapper.insert(summary);
        } catch (Exception e) {
            log.warn("AI 回复摘要持久化失败: sessionId={}, turn={}", session.sessionId, turn, e);
        }
    }

    private String buildGreeting(String emotionTag) {
        return switch (emotionTag) {
            case "happy" -> "嗨！看起来你今天心情不错呀！想和我聊聊什么开心的事吗？😊";
            case "sad" -> "嗨，我感觉到你今天有点难过。没关系，我在这里陪着你，想和我说说吗？💙";
            case "angry" -> "嗨，看起来你现在有些生气。生气是很正常的感受哦，想和我聊聊发生了什么吗？";
            case "scared" -> "嗨，我感觉到你有些害怕。别担心，这里很安全，我会一直陪着你。🌟";
            case "nervous" -> "嗨，看起来你有点紧张。深呼吸一下，我们慢慢聊，不着急。🌈";
            default -> "嗨！我是波波，今天想和我聊些什么呢？";
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
        final String gender;
        final AtomicInteger turnCount = new AtomicInteger(0);

        /** 上次活动时间（AUTH-030：用于累计每日使用时长） */
        private Instant lastActiveAt = Instant.now();

        /** 语音情绪历史（最近 10 条） */
        private final List<EmotionRecord> emotionHistory = new ArrayList<>();

        SessionState(UUID sessionId, UUID tenantId, UUID studentUserId, String emotionTag, String channel, String gender) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.studentUserId = studentUserId;
            this.emotionTag = emotionTag;
            this.channel = channel;
            this.gender = gender;
        }

        void addEmotionRecord(String emotion, double confidence) {
            emotionHistory.add(new EmotionRecord(emotion, confidence, Instant.now()));
            // 只保留最近 10 条
            if (emotionHistory.size() > 10) {
                emotionHistory.remove(0);
            }
        }

        /**
         * 标记本次活跃，返回距上次活动的秒数（上限 300s，避免长时间挂起累计虚高）。
         */
        long markActiveAndElapsed() {
            Instant now = Instant.now();
            long elapsed = java.time.Duration.between(lastActiveAt, now).getSeconds();
            lastActiveAt = now;
            return Math.max(0, Math.min(elapsed, 300));
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
