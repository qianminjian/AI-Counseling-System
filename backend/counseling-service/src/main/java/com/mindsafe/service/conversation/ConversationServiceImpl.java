package com.mindsafe.service.conversation;

import com.mindsafe.ai.ally.AllianceEnhancer;
import com.mindsafe.ai.cbt.CbtStageRouter;
import com.mindsafe.ai.orchestrator.PromptVariantRouter;
import com.mindsafe.service.experiment.ExperimentBucketAssigner;
import com.mindsafe.service.experiment.ExperimentMetricsCollector;
import com.mindsafe.service.offline.OfflineMessageReplayService;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.orchestrator.OrchestrationContext;
import com.mindsafe.ai.orchestrator.ProfileSignals;
import com.mindsafe.ai.orchestrator.PromptOrchestrationService;
import com.mindsafe.ai.orchestrator.StrategyProfile;
import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.risk.RiskDetectorService;
import com.mindsafe.ai.risk.RiskScoreCalculator;
import com.mindsafe.ai.risk.SemanticRiskClassifier;
import com.mindsafe.ai.safety.ConfidentialityNotice;
import com.mindsafe.ai.safety.CrisisResourceProvider;
import com.mindsafe.ai.safety.CrisisResources;
import com.mindsafe.ai.safety.HighSensitivityCategories;
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
import com.mindsafe.service.knowledge.RagAdvisorService;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.notification.NotificationService;
import com.mindsafe.service.profile.ProfileExtractorService;
import com.mindsafe.service.profile.StudentProfileService;
import com.mindsafe.service.prompt.PromptVersionService;
import com.mindsafe.service.quality.ConversationQualityService;
import com.mindsafe.service.security.FieldEncryptionService;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话服务实现（M1 核心闭环 + 风险识别 + DB 持久化）
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
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
    private final LongTermMemoryService longTermMemoryService;
    private final PromptVersionService promptVersionService;
    private final RagAdvisorService ragAdvisorService;
    private final SemanticRiskClassifier semanticRiskClassifier;
    private final PromptOrchestrationService promptOrchestrationService;
    private final ConversationQualityService conversationQualityService;
    private final FieldEncryptionService fieldEncryptionService;
    private final RiskScoreCalculator riskScoreCalculator;
    private final CrisisResourceProvider crisisResourceProvider;
    private final AllianceEnhancer allianceEnhancer;
    private final CbtStageRouter cbtStageRouter;
    private final ExperimentBucketAssigner experimentBucketAssigner;
    private final ExperimentMetricsCollector experimentMetricsCollector;
    private final SessionEndAnalyticsService sessionEndAnalyticsService;
    private final PromptVariantRouter promptVariantRouter;
    private final OfflineMessageReplayService offlineMessageReplayService;

    /** 活跃会话内存缓存（emotionTag 等非 DB 字段 + 轮次计数） */
    private final Map<UUID, SessionState> activeSessions = new ConcurrentHashMap<>();

    /** 冷场决策模型（无状态纯计算，design/28 §三） */
    private final NudgeDecisionModel nudgeDecisionModel = new NudgeDecisionModel();

    public ConversationServiceImpl(AiChatService aiChatService,
                                   PromptTemplateService promptTemplateService,
                                   RiskDetectorService riskDetectorService,
                                   PiiDesensitizer piiDesensitizer,
                                   CounselingSessionMapper sessionMapper,
                                   MessageSummaryMapper messageSummaryMapper,
                                   RiskEventMapper riskEventMapper,
                                   NotificationService notificationService,
                                   UserMapper userMapper,
                                   StudentProfileService profileService,
                                   ProfileExtractorService profileExtractorService,
                                   UsageTimeLimitService usageTimeLimitService,
                                   LongTermMemoryService longTermMemoryService,
                                   PromptVersionService promptVersionService,
                                   RagAdvisorService ragAdvisorService,
                                   SemanticRiskClassifier semanticRiskClassifier,
                                   PromptOrchestrationService promptOrchestrationService,
                                   ConversationQualityService conversationQualityService,
                                   FieldEncryptionService fieldEncryptionService,
                                   RiskScoreCalculator riskScoreCalculator,
                                   CrisisResourceProvider crisisResourceProvider,
                                   AllianceEnhancer allianceEnhancer,
                                   CbtStageRouter cbtStageRouter,
                                   ExperimentBucketAssigner experimentBucketAssigner,
                                   ExperimentMetricsCollector experimentMetricsCollector,
                                   SessionEndAnalyticsService sessionEndAnalyticsService,
                                   PromptVariantRouter promptVariantRouter,
                                   OfflineMessageReplayService offlineMessageReplayService) {
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
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
        this.longTermMemoryService = longTermMemoryService;
        this.promptVersionService = promptVersionService;
        this.ragAdvisorService = ragAdvisorService;
        this.semanticRiskClassifier = semanticRiskClassifier;
        this.promptOrchestrationService = promptOrchestrationService;
        this.conversationQualityService = conversationQualityService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.riskScoreCalculator = riskScoreCalculator;
        this.crisisResourceProvider = crisisResourceProvider;
        this.allianceEnhancer = allianceEnhancer;
        this.cbtStageRouter = cbtStageRouter;
        this.experimentBucketAssigner = experimentBucketAssigner;
        this.experimentMetricsCollector = experimentMetricsCollector;
        this.sessionEndAnalyticsService = sessionEndAnalyticsService;
        this.promptVariantRouter = promptVariantRouter;
        this.offlineMessageReplayService = offlineMessageReplayService;
    }

    @Override
    public SessionInfo createSession(UUID tenantId, UUID studentUserId, String emotionTag, String channel) {
        // 1. 持久化会话到 DB
        CounselingSession entity = CounselingSession.create(tenantId, studentUserId, emotionTag, channel);
        sessionMapper.insert(entity);

        UUID sessionId = entity.getSessionId();

        // 2. 内存缓存活跃会话状态（查询用户性别/昵称/年级用于 Prompt 个性化与问候语）
        User user = userMapper.selectById(studentUserId);
        String gender = (user != null) ? user.getGender() : null;
        String pseudonym = (user != null) ? user.getPseudonym() : null;
        int grade = parseGradeCode(user != null ? user.getGradeCode() : null);
        
        // 3. 问候语个性化："哈喽，[昵称]！" + 情绪问候（唤醒词 onboarding，design/28 §2.2）
        String greeting = buildGreeting(emotionTag, pseudonym);
        
        // ORCH-007：EMO-001 A/B 开场策略路由（确定性分桶，CRISIS 强制走 A）
        try {
            PromptVariantRouter.RouteResult variantRoute = promptVariantRouter.route(
                    studentUserId.toString(), "emo001", null);
            log.debug("开场策略路由: student={}, variant={}, bucket={}",
                    studentUserId, variantRoute.variant(), variantRoute.bucket());
        } catch (Exception e) {
            log.debug("开场策略路由降级: {}", e.getMessage());
        }

        // 3.5 SAFE-201：首次会话注入保密边界告知（design/14 §12.3，预审核模板）。
        // 告知完成标记复用 message_summary：senderType='ai' + turnCount=0（正常 AI 摘要 turn>=1，具唯一区分性），
        // 该记录同时作为合规审计凭据，不新增 DB 字段。
        if (!hasConfidentialityNotice(tenantId, studentUserId)) {
            String notice = ConfidentialityNotice.forGrade(grade);
            greeting = greeting + "\n\n" + notice;
            messageSummaryMapper.insert(MessageSummary.aiMessage(tenantId, sessionId, studentUserId, 0, notice));
            log.info("保密边界告知已注入并落库: sessionId={}, student={}, grade={}", sessionId, studentUserId, grade);
        }

        // 4. 加载学生画像沟通偏好（冷场决策模型信号 F，首次对话为 null 不阻塞）
        Double expressionDepth = profileService.getExpressionDepth(tenantId, studentUserId);
        
        // AB-001：实验分桶（确定性哈希，同班同组；当前以 studentUserId 为分配键，待班级字段补全后切换 classId）
        ExperimentBucketAssigner.Assignment experimentAssignment =
                experimentBucketAssigner.assignClass("default_exp", studentUserId.toString(), null);
        log.debug("AB 分桶: sessionId={}, variant={}, bucket={}", sessionId,
                experimentAssignment.variant(), experimentAssignment.bucket());

        activeSessions.put(sessionId, new SessionState(
                sessionId, tenantId, studentUserId, emotionTag, channel, gender, expressionDepth, grade));
        log.info("会话创建: sessionId={}, student={}, emotion={}, grade={}, expressionDepth={}",
                sessionId, studentUserId, emotionTag, grade, expressionDepth);

        return new SessionInfo(sessionId, greeting, Instant.now());
    }

    /** SAFE-201：该学生是否已完成保密边界告知（存在 senderType='ai' + turnCount=0 的告知记录） */
    private boolean hasConfidentialityNotice(UUID tenantId, UUID studentUserId) {
        Long count = messageSummaryMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getStudentUserId, studentUserId)
                        .eq(MessageSummary::getSenderType, "ai")
                        .eq(MessageSummary::getTurnCount, 0));
        return count != null && count > 0;
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

        // TOOL-003：离线消息幂等去重（clientMsgId 由前端传入，缺省时跳过）
        // 当前版本：仅记录能力接入点，待前端支持 clientMsgId 后启用完整去重
        if (content != null && content.startsWith("[offline_replay]")) {
            var dedup = offlineMessageReplayService.deduplicate(
                    sessionId + "_" + turn, java.util.Set.of());
            if (!dedup.accepted()) {
                log.info("离线消息重复，跳过: sessionId={}, turn={}", sessionId, turn);
                return Flux.empty();
            }
        }

        // AUTH-030：累计每日使用时长（按距上次消息的间隔，上限 5 分钟）
        long elapsedSec = session.markActiveAndElapsed();
        if (elapsedSec > 0) {
            usageTimeLimitService.addUsage(session.tenantId, session.studentUserId, elapsedSec);
        }

        // 记录语音情绪到会话历史（用于趋势追踪）
        if (voiceEmotion != null && voiceEmotionConfidence != null && voiceEmotionConfidence > 0.6) {
            session.addEmotionRecord(voiceEmotion, voiceEmotionConfidence);
        }

        // 1. 风险检测（文本关键词硬规则，用原文——需捕获"地址+自伤"等组合）
        RiskDetectionResult riskResult = riskDetectorService.detect(content);

        // 1.5 PII 服务端脱敏：硬规则检测已用原文完成，此后进入任何 LLM
        //     （语义风险分类 / 对话生成）的内容一律脱敏，确保原始 PII 不被 AI 复述或残留
        String safeContent = piiDesensitizer.desensitize(content);
        if (!safeContent.equals(content)) {
            log.info("PII 已脱敏: sessionId={}", sessionId);
        }

        // 1.6 RISK-202：M2 语义风险分类（SAF_001）——硬规则未达橙级时补召隐性/隐喻表达，
        //     只升不降（design/04 §18.3）；分类失败/超时降级纯硬规则结果，不阻断对话
        riskResult = applySemanticRisk(riskResult, safeContent, session);

        // 2. 多信号融合：文本风险 + 语音情绪
        RiskLevel fusedLevel = fuseRiskSignals(riskResult, voiceEmotion, voiceEmotionConfidence, session);
        boolean isRisky = fusedLevel != null;

        // SAFE-202：高敏场景前置化——命中虐待/丧失/自伤等类别即永久标记（不论级别）
        if (riskResult.isRisky() && HighSensitivityCategories.isHighSensitivity(riskResult.category())) {
            session.highSensitivity = true;
        }

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

            // RISK-201：红色风险 → 会话升级 escalated + 进入安全响应模式（后续硬短路跳过 LLM）
            if (fusedLevel == RiskLevel.RED) {
                session.enterSafetyMode();
                CounselingSession escalate = new CounselingSession();
                escalate.setSessionId(sessionId);
                escalate.setSessionStatus("escalated");
                escalate.setUpdatedAt(Instant.now());
                sessionMapper.updateById(escalate);

                log.error("🚨 红色风险预警(已升级+安全响应模式): sessionId={}, student={}, category={}",
                        sessionId, session.studentUserId, category);
            }
        }

        // 3.（已上移至 1.5）PII 脱敏完成，safeContent 后续进入 LLM / 对话记忆

        // 4. 持久化学生消息摘要（异步，不阻塞主流程）
        int riskLevelValue = fusedLevel != null ? fusedLevel.severity() : 0;
        persistStudentMessageSummary(session, turn, content, session.emotionTag, riskLevelValue);

        // 4.1 冷场决策模型信号更新：学生消息类型 + 风险快照（孩子说话即清零暖场计数）
        session.recordStudentMessage(classifyStudentMessage(content, fusedLevel != null, session.emotionTag));
        if (fusedLevel != null) {
            session.updateMaxRiskSeverity(fusedLevel.severity());
        }

        // 4.2 RISK-201：RED 硬短路——跳过 LLM 自由生成，返回预审核安全文案（design/04 §18.2）。
        //     短路不可被否定/引用降噪覆盖（fusedLevel 已经硬规则融合）；教师告警已在上方照发。
        //     安全响应模式：RED 触发后的后续轮次也不再自由生成，返回陪伴话术，解除需教师处置/新会话。
        if (fusedLevel == RiskLevel.RED || session.inSafetyMode()) {
            String safetyReply = (fusedLevel == RiskLevel.RED)
                    ? crisisResourceProvider.getRedSafetyReply(session.grade)
                    : CrisisResources.SAFETY_MODE_COMPANION_REPLY;
            persistAiMessageSummary(session, turn, safetyReply);
            session.recordAiReply(safetyReply);
            log.warn("RED 安全响应模式：跳过 LLM 自由生成: sessionId={}, turn={}, freshRed={}",
                    sessionId, turn, fusedLevel == RiskLevel.RED);
            return riskEvents.concatWith(Flux.just(
                    StreamMessageEvent.token(safetyReply),
                    StreamMessageEvent.done("")
            ));
        }

        // 4.5 AUTH-030：每日使用时长超限 → 引导休息（RED 已在 4.2 短路，此处不会拦截红色风险）
        if (usageTimeLimitService.isExceeded(session.tenantId, session.studentUserId)) {
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

        // 5. 调用 AI 服务获取流式回复（注入学生画像 + 长期记忆 + 年级适配，PROF-010/011/012/015 + AI-008 + AI-005）
        boolean riskBlocked = fusedLevel != null && fusedLevel.severity() >= RiskLevel.ORANGE.severity();
        int effectiveGrade = computeEffectiveGrade(session.grade, session.expressionDepth, riskBlocked);
        String profilePrompt = profileService.buildProfilePrompt(session.tenantId, session.studentUserId, session.grade, session.gender);
        // AI-008：追加长期记忆（跨会话关键事件回注）
        String memoryPrompt = longTermMemoryService.buildMemoryPrompt(session.tenantId, session.studentUserId);
        if (memoryPrompt != null) {
            profilePrompt = (profilePrompt != null ? profilePrompt + "\n\n" : "") + memoryPrompt;
        }

        // ALLY-201/203：治疗联盟增强——连续性开场 + 中断回归照护（design/52 §五）
        String alliancePrompt = buildAlliancePrompt(session, memoryPrompt);

        // AI-005：Prompt 版本 A/B 路由（DB 优先，classpath 降级）
        String gradeLevel = effectiveGrade <= 2 ? "1-2" : effectiveGrade <= 4 ? "3-4" : "5-6";
        PromptVersionService.ResolvedPrompt sysResolved = promptVersionService.resolve(
                session.tenantId, "SYS_001", session.studentUserId, Map.of(
                        "grade_level", gradeLevel,
                        "emotion_tag", session.emotionTag != null ? session.emotionTag : "",
                        "school_policy", "默认：发现高风险立即通知心理老师。",
                        "session_mode", "normal_counseling"
                ));
        String langKey = effectiveGrade <= 2 ? "LANG_001" : effectiveGrade <= 4 ? "LANG_002" : "LANG_003";
        PromptVersionService.ResolvedPrompt langResolved = promptVersionService.resolveRaw(
                session.tenantId, langKey, session.studentUserId);
        String systemPromptContent = sysResolved.content() + "\n\n" + langResolved.content();

        // ORCH-001/002/003/005：编排引擎——先算策略、再拼提示词（design/44 §四/§七）。
        // VCL-001：轮级 currentEmotion 由语音 SER 映射驱动（置信门控 >0.6），
        // ORCH-003：状态机输入上一轮 state/reliefCount，输出转移结果存回 session。
        // ORCH-005：冷场 nudge 信号并入编排（nudgeCount>0 且本轮无学生输入时触发）。
        String currentEmotion = (voiceEmotion != null && voiceEmotionConfidence != null && voiceEmotionConfidence > 0.6)
                ? promptOrchestrationService.mapVoiceEmotion(voiceEmotion) : null;
        ProfileSignals profileSignals = profileService.getProfileSignals(session.tenantId, session.studentUserId);
        boolean nudgeActive = session.nudgeCount.get() > 0;
        PromptOrchestrationService.Result orchResult = promptOrchestrationService.resolveWithTransition(
                new OrchestrationContext(session.grade, effectiveGrade, session.emotionTag,
                        currentEmotion, fusedLevel, profileSignals,
                        session.emotionState, session.reliefCount, nudgeActive, session.highSensitivity));
        StrategyProfile strategy = orchResult.profile();
        // 状态机转移结果存回会话（下一轮输入）
        session.emotionState = orchResult.transition().state();
        session.reliefCount = orchResult.transition().reliefCount();
        PromptVersionService.ResolvedPrompt emoResolved = promptVersionService.resolve(
                session.tenantId, "EMO_001", session.studentUserId,
                promptOrchestrationService.toTemplateVariables(strategy));
        systemPromptContent = systemPromptContent + "\n\n" + emoResolved.content();

        // CBT-201/202：CBT 阶段标记 + 年龄分层路由（design/52 §一，design/03 §11.3/11.4）
        CbtStageRouter.AgeStrategy ageStrategy = cbtStageRouter.resolveAgeStrategy(effectiveGrade);
        log.debug("CBT 年龄分层: sessionId={}, grade={}, strategy={}", sessionId, effectiveGrade, ageStrategy);

        // ALLY 连续性开场 / 回归照护注入 System Prompt
        if (alliancePrompt != null) {
            systemPromptContent = systemPromptContent + "\n\n" + alliancePrompt;
        }

        // KB-101b：RAG 参考知识注入（design/49 §六）——场景触发才检索，寒暄闲聊不检索；
        // RED 危机场景已在 4.2 硬短路，不会走到此处；检索异常返回空串不影响主线。
        String ragContext = ragAdvisorService.buildRagContext(session.tenantId, safeContent, effectiveGrade);
        if (!ragContext.isEmpty()) {
            systemPromptContent = systemPromptContent + "\n\n" + ragContext;
            log.info("RAG 参考知识已注入: sessionId={}, contextLen={}", sessionId, ragContext.length());
        }

        // 记录 Prompt 版本到会话（用于 A/B 效果对比）
        String versionTag = sysResolved.versionTag();
        CounselingSession versionUpdate = new CounselingSession();
        versionUpdate.setSessionId(sessionId);
        versionUpdate.setPromptVersion(versionTag);
        versionUpdate.setUpdatedAt(Instant.now());
        sessionMapper.updateById(versionUpdate);

        StringBuilder aiResponseCollector = new StringBuilder();
        Flux<StreamMessageEvent> aiStream = aiChatService.chatWithPrompt(sessionId, session.emotionTag, safeContent, session.gender, profilePrompt, effectiveGrade, systemPromptContent)
                .doOnNext(event -> {
                    if (event.type() != null && event.type().equals("token") && event.content() != null) {
                        aiResponseCollector.append(event.content());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    // AI 回复完成后持久化摘要
                    String fullReply = aiResponseCollector.toString();
                    persistAiMessageSummary(session, turn, fullReply);
                    // 冷场决策模型信号：AI 是否刚问了思考型问题
                    session.recordAiReply(fullReply);
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
    public Flux<StreamMessageEvent> sendNudgeStream(UUID tenantId, UUID sessionId, int silenceSeconds) {
        SessionState session = activeSessions.get(sessionId);
        if (session == null) {
            log.debug("nudge: 会话不存在，返回空流: sessionId={}", sessionId);
            return Flux.empty();
        }

        // 护栏 1：会话已 escalated（红色风险）→ 不做日常暖场（安全流程接管）
        if (isSessionEscalated(sessionId)) {
            log.debug("nudge: 会话已 escalated，返回空流: sessionId={}", sessionId);
            return Flux.empty();
        }

        // 护栏 2：连续暖场次数（≤2）/ 间隔（≥20s），孩子说话即清零
        if (!session.canNudge()) {
            log.debug("nudge: 护栏拦截（次数/间隔），返回空流: sessionId={}, nudgeCount={}",
                    sessionId, session.nudgeCount.get());
            return Flux.empty();
        }

        // 冷场决策模型（design/28 §三 3.2）：多信号加权 → 留白/轻陪伴/引导破冰
        boolean riskBlocked = session.maxRiskSeverity() >= RiskLevel.ORANGE.severity();
        NudgeDecisionModel.NudgeDecision decision = nudgeDecisionModel.decide(new NudgeDecisionModel.NudgeContext(
                session.emotionTag,
                silenceSeconds,
                session.lastStudentMessageType(),
                session.lastAiAskedThinkingQuestion(),
                session.turnCount.get(),
                riskBlocked,
                session.secondsSinceLastStudentMessage(),
                session.expressionDepth
        ));

        if (decision.warmthLevel() == 0) {
            // 留白：把安静还给孩子（他可能在思考），前端不做任何事
            log.debug("nudge: 决策=留白，返回空流: sessionId={}, silenceSeconds={}", sessionId, silenceSeconds);
            return Flux.empty();
        }

        // 暖场：渲染 TSK-004 指令（追加到 system 层，不向记忆写伪造学生消息）
        String nudgeInstruction = promptTemplateService.render(PromptTemplateService.TSK_004, Map.of(
                "silence_seconds", String.valueOf(silenceSeconds),
                "warmth_level", String.valueOf(decision.warmthLevel()),
                "direction", decision.direction()
        ));
        String profilePrompt = profileService.buildProfilePrompt(session.tenantId, session.studentUserId, session.grade, session.gender);
        // AI-008：暖场也回注长期记忆（让暖场更个性化，如"上次你说喜欢画画"）
        String nudgeMemoryPrompt = longTermMemoryService.buildMemoryPrompt(session.tenantId, session.studentUserId);
        if (nudgeMemoryPrompt != null) {
            profilePrompt = (profilePrompt != null ? profilePrompt + "\n\n" : "") + nudgeMemoryPrompt;
        }
        // PROF-015：暖场场景无风险（橙/红已拦截），仅根据表达深度降级
        int effectiveGrade = computeEffectiveGrade(session.grade, session.expressionDepth, false);
        int turn = session.turnCount.get();
        StringBuilder aiResponseCollector = new StringBuilder();

        session.markNudged();
        log.info("nudge: 决策=暖场: sessionId={}, warmthLevel={}, direction={}, silenceSeconds={}",
                sessionId, decision.warmthLevel(), decision.direction(), silenceSeconds);

        return aiChatService.chatProactive(sessionId, session.emotionTag, session.gender, profilePrompt, nudgeInstruction, effectiveGrade)
                .doOnNext(event -> {
                    if (event.type() != null && event.type().equals("token") && event.content() != null) {
                        aiResponseCollector.append(event.content());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String fullReply = aiResponseCollector.toString();
                    persistAiMessageSummary(session, turn, fullReply);
                    session.recordAiReply(fullReply);
                    return Flux.just(StreamMessageEvent.done(""));
                }))
                .onErrorResume(e -> {
                    // 暖场失败静默处理（不给孩子展示错误，不打扰氛围）
                    log.error("nudge: AI 调用异常，返回空流: sessionId={}", sessionId, e);
                    return Flux.empty();
                });
    }

    /** 查询会话是否已 escalated（红色风险接管）；查询失败返回 false（决策模型层有风险信号兜底） */
    private boolean isSessionEscalated(UUID sessionId) {
        try {
            CounselingSession entity = sessionMapper.selectById(sessionId);
            return entity != null && "escalated".equals(entity.getSessionStatus());
        } catch (Exception e) {
            log.warn("nudge: 查询会话状态失败: sessionId={}", sessionId, e);
            return false;
        }
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

            // AB-002：采集会话深度指标（异步聚合，不阻塞主流程）
            try {
                ExperimentMetricsCollector.MetricEvent depthEvent = new ExperimentMetricsCollector.MetricEvent(
                        "default_exp", "CONTROL", session.studentUserId.toString(),
                        ExperimentMetricsCollector.MetricType.SESSION_DEPTH,
                        session.turnCount.get(), java.time.LocalDate.now());
                log.debug("AB 指标采集: sessionId={}, metric=SESSION_DEPTH, value={}", sessionId, depthEvent.value());
            } catch (Exception e) {
                log.debug("AB 指标采集失败（不影响业务）: {}", e.getMessage());
            }

            // 异步生成 AI 会话摘要（摘要完成后触发 PROF-003 画像 LLM 提炼）
            generateSummaryAsync(tenantId, sessionId, session.studentUserId);

            // 异步更新学生画像（基于历史会话统计；VCL-001：本会话语音情绪聚合回注 emotionBaseline.voice，
            // 只传可映射到规范集的标签，聚合衍生特征不留逐条流水，design/47 §5.1）
            List<String> voiceEmotions = session.emotionLabels().stream()
                    .map(promptOrchestrationService::mapVoiceEmotion)
                    .filter(Objects::nonNull)
                    .toList();
            profileService.updateProfile(session.tenantId, session.studentUserId, voiceEmotions);

            // RISK-204 / ORCH-008 / PROF-024：会话结束分析（趋势+效果量化，异步不阻塞）
            try {
                sessionEndAnalyticsService.analyze(
                        session.tenantId, session.studentUserId,
                        voiceEmotions, // 近期情绪（当前仅本会话，后续扩展跨会话查询）
                        session.emotionLabels(),
                        List.of(), // studentMessages 待后续从 DB 补充
                        session.emotionTag);
            } catch (Exception e) {
                log.debug("会话结束分析降级: {}", e.getMessage());
            }
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
                // R-01：contentSummary 落库时字段级加密，拼接前解密（明文数据兼容透传）
                sb.append(role).append(": ").append(fieldEncryptionService.decrypt(m.getContentSummary())).append("\n");
            }
            String conversationText = sb.toString();

            // PEVAL-001：异步评估会话质量并落库（服务内部按抽样率决定是否评估，失败静默降级）
            conversationQualityService.evaluateSessionAsync(tenantId, sessionId, conversationText);

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

                // 5. AI-008：提取跨会话关键事件（长期记忆）
                longTermMemoryService.extractAndStoreKeyEvents(tenantId, studentUserId, sessionId, conversationText, summary);
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

            // RISK-203：结构化风险评分（C-SSRS 儿童适配，可解释 reason_codes 供教师复核）
            RiskScoreCalculator.ScoreInput scoreInput = new RiskScoreCalculator.ScoreInput(
                    riskResult.score(),           // categoryBaseScore
                    0,                            // intentWeight（待语义层抽取）
                    0,                            // planWeight
                    10,                           // recencyWeight（当前会话=今天）
                    0,                            // actionWeight
                    0,                            // repetitionWeight
                    0,                            // protectiveWeight
                    0,                            // falsePositivePenalty
                    0.8,                          // confidenceAdjustment（硬规则默认 0.8）
                    riskResult.level().severity() >= RiskLevel.RED.severity() ? riskResult.level() : null,
                    null,                         // cssrsIdeation（待语义层抽取）
                    null                          // cssrsBehavior
            );
            RiskScoreCalculator.ScoreResult scoreResult = riskScoreCalculator.calculate(scoreInput);
            log.info("风险评分计算: sessionId={}, score={}, level={}, reasons={}",
                    session.sessionId, scoreResult.score(), scoreResult.level(), scoreResult.reasonCodes());

            riskEventMapper.insert(event);
            log.info("风险事件已持久化: riskEventId={}, level={}, score={}",
                    event.getRiskEventId(), riskResult.level(), scoreResult.score());

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
            // R-01：学生消息内容字段级加密后落库（工厂已截断至 1024，再对截断后明文加密）
            summary.setContentSummary(fieldEncryptionService.encrypt(summary.getContentSummary()));
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
            // R-01：AI 回复内容字段级加密后落库
            summary.setContentSummary(fieldEncryptionService.encrypt(summary.getContentSummary()));
            messageSummaryMapper.insert(summary);
        } catch (Exception e) {
            log.warn("AI 回复摘要持久化失败: sessionId={}, turn={}", session.sessionId, turn, e);
        }
    }

    /**
     * 解析 gradeCode 为年级数字（1-6）。
     * <p>
     * 支持格式："G1"~"G6"、"1"~"6"、null/空/无法解析 → 默认 4（中间值，design/29 §3.3）
     * <p>
     * public：供 VoicePersonaResolver（TMATCH-001，voice 包）复用同一年级解析口径。
     */
    public static int parseGradeCode(String gradeCode) {
        if (gradeCode == null || gradeCode.isBlank()) return 4;
        String cleaned = gradeCode.trim().toUpperCase();
        // 去掉 "G" 前缀（如 "G3" → "3"）
        if (cleaned.startsWith("G")) {
            cleaned = cleaned.substring(1);
        }
        try {
            int grade = Integer.parseInt(cleaned);
            return (grade >= 1 && grade <= 6) ? grade : 4;
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /**
     * ALLY-201/203：构建治疗联盟增强 Prompt（连续性开场 + 中断回归照护）。
     * <p>
     * 利用记忆回注摘要生成续接话术；失败安全：无记忆 → 返回 null（不注入）。
     */
    private String buildAlliancePrompt(SessionState session, String memoryPrompt) {
        try {
            // ALLY-201：连续性开场（有记忆回注时生成续接提示）
            if (memoryPrompt != null && !memoryPrompt.isBlank()) {
                String firstLine = memoryPrompt.lines()
                        .filter(l -> l.startsWith("- "))
                        .findFirst()
                        .map(l -> l.substring(2).trim())
                        .orElse(null);
                return allianceEnhancer.buildContinuityPrompt(firstLine, "波波");
            }
            return null;
        } catch (Exception e) {
            log.debug("ALLY 联盟增强构建失败（不影响对话）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * PROF-015：动态降级机制——根据表达深度调整语言复杂度。
     * <p>
     * 规则（design/29 §3.11）：
     * <ul>
     *   <li>expressionDepth < 0.15（极端沉默）→ 直接使用 1-2 年级模板（effectiveGrade=1）</li>
     *   <li>expressionDepth < 0.3 且 grade > 2 → 降 2 个年级（如 5→3）</li>
     *   <li>风险场景（橙/红）→ 不降级（安全话术需要认知匹配）</li>
     * </ul>
     *
     * @param grade           实际年级（1-6）
     * @param expressionDepth 画像表达深度（null 表示无数据，不降级）
     * @param riskBlocked     是否处于风险场景（橙/红）
     * @return 有效年级（用于选择语言模板）
     */
    static int computeEffectiveGrade(int grade, Double expressionDepth, boolean riskBlocked) {
        if (riskBlocked || expressionDepth == null) {
            return grade;
        }
        if (expressionDepth < 0.15) {
            return 1; // 极端沉默 → 直接用最简单语言
        }
        if (expressionDepth < 0.3 && grade > 2) {
            return Math.max(1, grade - 2);
        }
        return grade;
    }
    
    /**
     * RISK-201：RED 短路安全文案选择（分年级两版，预审核模板，不由 LLM 生成）
     * <p>
     * 1-2 年级 → 短句版；3-6 年级 → 标准版（含热线，design/04 §18.2）。
     */
    static String redSafetyReply(int grade) {
        return grade <= 2
                ? CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE
                : CrisisResources.RED_SAFETY_REPLY;
    }

    /**
     * 构建问候语：个性化“哈喽，[昵称]！” + 情绪问候（design/28 §2.2）
     * <p>
     * 唤醒词 onboarding：用"哈喽+名字"模式自然引导孩子回应"哈喽波波"；
     * 始终生效（不依赖语音唤醒模式）；昵称缺失时回退通用问候。
     */
    private String buildGreeting(String emotionTag, String pseudonym) {
        String hello = (pseudonym != null && !pseudonym.isBlank())
                ? "哈喽，" + pseudonym + "！"
                : "哈喽！";
        String emotionGreeting = switch (emotionTag) {
            case "happy" -> "看起来你今天心情不错呀！想和我聊聊什么开心的事吗？😊";
            case "sad" -> "我感觉到你今天有点难过。没关系，我在这里陪着你，想和我说说吗？💙";
            case "angry" -> "看起来你现在有些生气。生气是很正常的感受哦，想和我聊聊发生了什么吗？";
            case "scared" -> "我感觉到你有些害怕。别担心，这里很安全，我会一直陪着你。🌟";
            case "nervous" -> "看起来你有点紧张。深呼吸一下，我们慢慢聊，不着急。🌈";
            default -> "我是波波，今天想和我聊些什么呢？";
        };
        return hello + emotionGreeting;
    }

    /**
     * RISK-202：M2 语义风险分类融合（design/04 §18.3）
     * <p>
     * 分工铁律：硬规则管"宁多报"（RED/ORANGE 已接住，不再调语义层，更不可降级）；
     * 语义层管"隐性补召"（隐喻/告别行为/送东西/网络黑话），只能升级风险档位。
     * 升级结果进入 fuseRiskSignals 融合，天然联动 RISK-201 RED 硬短路与教师告警。
     * 世界B 分诊段接线前的先行实施：非流式前置单次调用，超时门禁 ≤800ms（分类器内部控制）。
     */
    private RiskDetectionResult applySemanticRisk(RiskDetectionResult keywordResult,
                                                  String safeContent, SessionState session) {
        // 硬规则已达橙/红 → 语义层不参与（省 LLM 调用，且语义层无权降级，§九铁律）
        if (keywordResult.level().severity() >= RiskLevel.ORANGE.severity()) {
            return keywordResult;
        }
        RiskLevel semanticLevel = semanticRiskClassifier.classify(safeContent, null, null, session.grade);
        if (semanticLevel == null || semanticLevel.severity() <= keywordResult.level().severity()) {
            return keywordResult; // 无语义风险或未超过关键词档位（只升不降）
        }
        log.warn("语义分类升级风险等级: keyword={}, semantic={}", keywordResult.level(), semanticLevel);
        int score = semanticLevel == RiskLevel.RED ? 85 : semanticLevel == RiskLevel.ORANGE ? 60 : 40;
        return new RiskDetectionResult(semanticLevel, "llm_semantic", List.of(), score, false,
                "语义分析识别到隐性风险表达（隐喻/暗示），请结合原文人工复核");
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

    // ===== 冷场决策模型辅助（design/28 §三 3.2 信号 C） =====

    /** 敷衍回答词集（"嗯/哦/不知道"类短答） */
    private static final java.util.Set<String> PERFUNCTORY_REPLIES = java.util.Set.of(
            "嗯", "哦", "喔", "好", "好的", "是", "是的", "啊", "行", "可以",
            "不知道", "不晓得", "随便", "还行", "还好", "嗯嗯", "哦哦", "没有", "没", "不想说");

    /** 负面情绪标签集（用于轻微倾诉判定：表达了感受但未命中风险信号） */
    private static final java.util.Set<String> DISTRESS_EMOTIONS = java.util.Set.of(
            "sad", "angry", "scared", "nervous");

    /**
     * 分类学生消息类型（信号 C）：沉重倾诉（命中风险信号）/ 敷衍回答 / 轻微倾诉 / 普通
     * <p>
     * 轻微倾诉：负面情绪 + 有一定内容长度（表达了感受，但未命中风险信号）→ 决策模型只轻陪伴不深挖。
     */
    private String classifyStudentMessage(String content, boolean risky, String emotionTag) {
        if (risky) {
            return NudgeDecisionModel.MSG_HEAVY;
        }
        String stripped = content == null ? "" : content.replaceAll("[\\s，。！？!?~～…·、\"'“”（）()]", "");
        if (!stripped.isEmpty() && stripped.length() <= 5 && PERFUNCTORY_REPLIES.contains(stripped)) {
            return NudgeDecisionModel.MSG_PERFUNCTORY;
        }
        // 轻微倾诉：负面情绪 + 有内容（如"没人和我玩"），未命中风险信号
        if (stripped.length() > 5 && emotionTag != null && DISTRESS_EMOTIONS.contains(emotionTag)) {
            return NudgeDecisionModel.MSG_DISCLOSURE;
        }
        return NudgeDecisionModel.MSG_NORMAL;
    }

    /** 思考型问题引导词（AI 正在引导反思，孩子的沉默可能是在思考） */
    private static final String[] THINKING_CUES = {
            "脑袋里冒出", "你在想什么", "你觉得为什么", "你心里是什么感觉",
            "当时发生了什么", "你会怎么做", "是什么感觉", "能多说说"
    };

    /**
     * 判断 AI 回复是否以思考型问题收尾（信号 C：是则延长留白）
     */
    private static boolean isThinkingQuestion(String aiReply) {
        if (aiReply == null || aiReply.isBlank()) return false;
        boolean hasQuestion = aiReply.contains("？") || aiReply.contains("?");
        if (!hasQuestion) return false;
        for (String cue : THINKING_CUES) {
            if (aiReply.contains(cue)) return true;
        }
        return false;
    }

    /** 内存会话状态（含情绪趋势追踪 + 冷场决策信号 + 年级适配） */
    private static class SessionState {
        final UUID sessionId;
        final UUID tenantId;
        final UUID studentUserId;
        final String emotionTag;
        final String channel;
        final String gender;
        final AtomicInteger turnCount = new AtomicInteger(0);

        /** PROF-010：学生年级（1-6，解析失败默认 4） */
        final int grade;

        /** 信号 F：画像沟通偏好 expression_depth（nullable，首次对话为 null → F 计 0） */
        final Double expressionDepth;

        /** 上次活动时间（AUTH-030：用于累计每日使用时长） */
        private Instant lastActiveAt = Instant.now();

        /** 语音情绪历史（最近 10 条） */
        private final List<EmotionRecord> emotionHistory = new ArrayList<>();

        // ===== 冷场决策模型（nudge）状态，design/28 §三 3.4 =====

        /** 连续暖场计数（学生说话即清零，连续上限 2 次） */
        final AtomicInteger nudgeCount = new AtomicInteger(0);
        /** 上次暖场时间（间隔 ≥20s 才允许再次暖场） */
        private volatile Instant lastNudgeAt;
        /** 信号 C：最后一条学生消息类型（normal/perfunctory/heavy） */
        private volatile String lastStudentMessageType = NudgeDecisionModel.MSG_NORMAL;
        /** 孩子上次说话时间（沉重倾诉宽限期判断） */
        private volatile Instant lastStudentMessageAt = Instant.now();
        /** 信号 C：AI 最后一句是否为思考型问题 */
        private volatile boolean lastAiAskedThinkingQuestion = false;
        /** 信号 E：本会话出现过的最高融合风险 severity */
        private volatile int maxRiskSeverity = 0;

        /** RISK-201：安全响应模式（RED 短路后限制自由生成，解除需教师处置/新会话） */
        private final AtomicBoolean safetyMode = new AtomicBoolean(false);

        /** ORCH-003：情绪状态机会话级状态（轮级更新） */
        private volatile StrategyProfile.EmotionState emotionState = StrategyProfile.EmotionState.STABLE;
        private volatile int reliefCount = 0;

        /** SAFE-202：高敏模式标记（命中虐待/丧失/自伤等类别后永久标记，不回落） */
        private volatile boolean highSensitivity = false;

        SessionState(UUID sessionId, UUID tenantId, UUID studentUserId, String emotionTag,
                     String channel, String gender, Double expressionDepth, int grade) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.studentUserId = studentUserId;
            this.emotionTag = emotionTag;
            this.channel = channel;
            this.gender = gender;
            this.expressionDepth = expressionDepth;
            this.grade = grade;
        }

        void addEmotionRecord(String emotion, double confidence) {
            emotionHistory.add(new EmotionRecord(emotion, confidence, Instant.now()));
            // 只保留最近 10 条
            if (emotionHistory.size() > 10) {
                emotionHistory.remove(0);
            }
        }

        /** VCL-001：本会话语音情绪标签快照（原始 SER 标签，会话结束聚合回注画像用） */
        List<String> emotionLabels() {
            return emotionHistory.stream().map(EmotionRecord::emotion).toList();
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

        // ===== 冷场决策模型（nudge）方法 =====

        /** 记录学生消息：更新消息类型 + 清零暖场计数（孩子一说话即重置） */
        void recordStudentMessage(String messageType) {
            this.lastStudentMessageType = messageType;
            this.lastStudentMessageAt = Instant.now();
            this.nudgeCount.set(0);
        }

        /** 记录 AI 回复：判断是否以思考型问题收尾 */
        void recordAiReply(String aiReply) {
            this.lastAiAskedThinkingQuestion = isThinkingQuestion(aiReply);
        }

        /** 更新本会话最高风险 severity */
        void updateMaxRiskSeverity(int severity) {
            if (severity > this.maxRiskSeverity) {
                this.maxRiskSeverity = severity;
            }
        }

        int maxRiskSeverity() { return maxRiskSeverity; }
        String lastStudentMessageType() { return lastStudentMessageType; }
        boolean lastAiAskedThinkingQuestion() { return lastAiAskedThinkingQuestion; }

        // ===== RISK-201：安全响应模式 =====

        void enterSafetyMode() { safetyMode.set(true); }
        boolean inSafetyMode() { return safetyMode.get(); }

        long secondsSinceLastStudentMessage() {
            return java.time.Duration.between(lastStudentMessageAt, Instant.now()).getSeconds();
        }

        /** 暖场护栏：连续 ≤2 次 且 距上次暖场 ≥20s */
        boolean canNudge() {
            if (nudgeCount.get() >= 2) return false;
            Instant last = lastNudgeAt;
            return last == null || java.time.Duration.between(last, Instant.now()).getSeconds() >= 20;
        }

        void markNudged() {
            nudgeCount.incrementAndGet();
            lastNudgeAt = Instant.now();
        }

        record EmotionRecord(String emotion, double confidence, Instant timestamp) {}
    }
}
