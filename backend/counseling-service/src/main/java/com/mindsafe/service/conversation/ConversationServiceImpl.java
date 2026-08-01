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
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.knowledge.RagAdvisorService;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.profile.StudentProfileService;
import com.mindsafe.service.prompt.PromptVersionService;
import com.mindsafe.service.usage.UsageTimeLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 对话服务实现（M1 核心闭环 + 风险识别 + DB 持久化）
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final ConversationRiskProcessor riskProcessor;
    private final PiiDesensitizer piiDesensitizer;
    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final UserMapper userMapper;
    private final StudentProfileService profileService;
    private final LongTermMemoryService longTermMemoryService;
    private final PromptVersionService promptVersionService;
    private final UsageTimeLimitService usageTimeLimitService;
    private final RagAdvisorService ragAdvisorService;
    private final PromptOrchestrationService promptOrchestrationService;
    private final MessageSummaryService messageSummaryService;
    private final CrisisResourceProvider crisisResourceProvider;
    private final AllianceEnhancer allianceEnhancer;
    private final CbtStageRouter cbtStageRouter;
    private final ExperimentBucketAssigner experimentBucketAssigner;
    private final ExperimentMetricsCollector experimentMetricsCollector;
    private final SessionEndAnalyticsService sessionEndAnalyticsService;
    private final PromptVariantRouter promptVariantRouter;
    private final OfflineMessageReplayService offlineMessageReplayService;
    private final RedisSessionStateStore sessionStateStore;
    private final ConversationContextAgent contextAgent;
    private final SessionSummaryUpdater sessionSummaryUpdater;

    /** 冷场决策模型（无状态纯计算，design/28 §三） */
    private final NudgeDecisionModel nudgeDecisionModel = new NudgeDecisionModel();

    public ConversationServiceImpl(AiChatService aiChatService,
                                   PromptTemplateService promptTemplateService,
                                   ConversationRiskProcessor riskProcessor,
                                   PiiDesensitizer piiDesensitizer,
                                   CounselingSessionMapper sessionMapper,
                                   MessageSummaryMapper messageSummaryMapper,
                                   UserMapper userMapper,
                                   StudentProfileService profileService,
                                   UsageTimeLimitService usageTimeLimitService,
                                   LongTermMemoryService longTermMemoryService,
                                   PromptVersionService promptVersionService,
                                   RagAdvisorService ragAdvisorService,
                                   PromptOrchestrationService promptOrchestrationService,
                                   MessageSummaryService messageSummaryService,
                                   CrisisResourceProvider crisisResourceProvider,
                                   AllianceEnhancer allianceEnhancer,
                                   CbtStageRouter cbtStageRouter,
                                   ExperimentBucketAssigner experimentBucketAssigner,
                                   ExperimentMetricsCollector experimentMetricsCollector,
                                   SessionEndAnalyticsService sessionEndAnalyticsService,
                                   PromptVariantRouter promptVariantRouter,
                                   OfflineMessageReplayService offlineMessageReplayService,
                                   RedisSessionStateStore sessionStateStore,
                                   ConversationContextAgent contextAgent,
                                   SessionSummaryUpdater sessionSummaryUpdater) {
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.riskProcessor = riskProcessor;
        this.piiDesensitizer = piiDesensitizer;
        this.sessionMapper = sessionMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.userMapper = userMapper;
        this.profileService = profileService;
        this.usageTimeLimitService = usageTimeLimitService;
        this.longTermMemoryService = longTermMemoryService;
        this.promptVersionService = promptVersionService;
        this.ragAdvisorService = ragAdvisorService;
        this.promptOrchestrationService = promptOrchestrationService;
        this.messageSummaryService = messageSummaryService;
        this.crisisResourceProvider = crisisResourceProvider;
        this.allianceEnhancer = allianceEnhancer;
        this.cbtStageRouter = cbtStageRouter;
        this.experimentBucketAssigner = experimentBucketAssigner;
        this.experimentMetricsCollector = experimentMetricsCollector;
        this.sessionEndAnalyticsService = sessionEndAnalyticsService;
        this.promptVariantRouter = promptVariantRouter;
        this.offlineMessageReplayService = offlineMessageReplayService;
        this.sessionStateStore = sessionStateStore;
        this.contextAgent = contextAgent;
        this.sessionSummaryUpdater = sessionSummaryUpdater;
    }

    @Transactional
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
        int grade = ConversationUtils.parseGradeCode(user != null ? user.getGradeCode() : null);
        
        // 3. 问候语个性化："哈喽，[昵称]！" + 情绪问候（唤醒词 onboarding，design/28 §2.2）
        String greeting = ConversationUtils.buildGreeting(emotionTag, pseudonym);
        
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

        SessionState newState = new SessionState(
                sessionId, tenantId, studentUserId, emotionTag, channel, gender, expressionDepth, grade);
        newState.setPseudonym(pseudonym);  // CTX-Agent：身份简报用
        sessionStateStore.save(sessionId, newState);
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
        SessionState session = sessionStateStore.get(sessionId);
        if (session == null) {
            return Flux.just(StreamMessageEvent.error("会话不存在"));
        }

        int turn = session.incrementTurnCount();
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
            usageTimeLimitService.addUsage(session.getTenantId(), session.getStudentUserId(), elapsedSec);
        }

        // 记录语音情绪到会话历史（用于趋势追踪）
        if (voiceEmotion != null && voiceEmotionConfidence != null && voiceEmotionConfidence > 0.6) {
            session.addEmotionRecord(voiceEmotion, voiceEmotionConfidence);
        }

        // 1. 风险检测（文本关键词硬规则，用原文——需捕获"地址+自伤"等组合）
        RiskDetectionResult riskResult = riskProcessor.detectKeywordRisk(content);

        // 1.5 PII 服务端脱敏：硬规则检测已用原文完成，此后进入任何 LLM
        //     （语义风险分类 / 对话生成）的内容一律脱敏，确保原始 PII 不被 AI 复述或残留
        String safeContent = piiDesensitizer.desensitize(content);
        if (!safeContent.equals(content)) {
            log.info("PII 已脱敏: sessionId={}", sessionId);
        }

        // 1.6 RISK-202：M2 语义风险分类（SAF_001）——硬规则未达橙级时补召隐性/隐喻表达，
        //     只升不降（design/04 §18.3）；分类失败/超时降级纯硬规则结果，不阻断对话
        riskResult = riskProcessor.applySemanticRisk(riskResult, safeContent, session.getGrade());

        // 2. 多信号融合：文本风险 + 语音情绪
        RiskLevel fusedLevel = riskProcessor.fuseRiskSignals(riskResult, voiceEmotion, voiceEmotionConfidence, session.consecutiveNegativeCount());
        boolean isRisky = fusedLevel != null;

        // SAFE-202：高敏场景前置化——命中虐待/丧失/自伤等类别即永久标记（不论级别）
        if (riskResult.isRisky() && HighSensitivityCategories.isHighSensitivity(riskResult.category())) {
            session.setHighSensitivity(true);
        }

        Flux<StreamMessageEvent> riskEvents = Flux.empty();

        if (isRisky) {
            String category = riskResult.isRisky() ? riskResult.category() : "voice_emotion";
            log.warn("风险识别(融合): sessionId={}, level={}, textRisk={}, voiceEmotion={}, consecutiveNegative={}",
                    sessionId, fusedLevel, riskResult.level(), voiceEmotion, session.consecutiveNegativeCount());

            // 持久化风险事件到 DB
            String suggestion = riskResult.isRisky() ? riskResult.suggestion() : riskProcessor.buildEmotionSuggestion(voiceEmotion);
            RiskDetectionResult fusedResult = new RiskDetectionResult(
                    fusedLevel, category, riskResult.matchedKeywords(),
                    riskResult.score(), false, suggestion
            );
            riskProcessor.persistRiskEvent(session, fusedResult);

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
                        sessionId, session.getStudentUserId(), category);
            }
        }

        // 3.（已上移至 1.5）PII 脱敏完成，safeContent 后续进入 LLM / 对话记忆

        // 4. 持久化学生消息摘要（异步，不阻塞主流程）
        int riskLevelValue = fusedLevel != null ? fusedLevel.severity() : 0;
        messageSummaryService.persistStudentMessageSummary(session, turn, content, session.getEmotionTag(), riskLevelValue);

        // 4.1 冷场决策模型信号更新：学生消息类型 + 风险快照（孩子说话即清零暖场计数）
        session.recordStudentMessage(ConversationUtils.classifyStudentMessage(content, fusedLevel != null, session.getEmotionTag()));
        if (fusedLevel != null) {
            session.updateMaxRiskSeverity(fusedLevel.severity());
        }

        // 4.1b CTX-Agent Phase 5：主题线索提取（轻量规则，零 LLM）
        extractTopicHint(session, content, riskResult, turn);

        // 持久化本轮状态变更（覆盖 RED 短路 / 时长超限等提前返回路径）
        sessionStateStore.save(sessionId, session);

        // 4.2 RISK-201：RED 硬短路——跳过 LLM 自由生成，返回预审核安全文案（design/04 §18.2）。
        //     短路不可被否定/引用降噪覆盖（fusedLevel 已经硬规则融合）；教师告警已在上方照发。
        //     安全响应模式：RED 触发后的后续轮次也不再自由生成，返回陪伴话术，解除需教师处置/新会话。
        if (fusedLevel == RiskLevel.RED || session.inSafetyMode()) {
            String safetyReply = (fusedLevel == RiskLevel.RED)
                    ? crisisResourceProvider.getRedSafetyReply(session.getGrade())
                    : CrisisResources.SAFETY_MODE_COMPANION_REPLY;
            messageSummaryService.persistAiMessageSummary(session, turn, safetyReply);
            session.recordAiReply(safetyReply);
            sessionStateStore.save(sessionId, session);
            log.warn("RED 安全响应模式：跳过 LLM 自由生成: sessionId={}, turn={}, freshRed={}",
                    sessionId, turn, fusedLevel == RiskLevel.RED);
            return riskEvents.concatWith(Flux.just(
                    StreamMessageEvent.token(safetyReply),
                    StreamMessageEvent.done("")
            ));
        }

        // 4.5 AUTH-030：每日使用时长超限 → 引导休息（RED 已在 4.2 短路，此处不会拦截红色风险）
        if (usageTimeLimitService.isExceeded(session.getTenantId(), session.getStudentUserId())) {
            log.info("每日使用时长已达上限，引导休息: sessionId={}, student={}, usedSec={}",
                    sessionId, session.getStudentUserId(),
                    usageTimeLimitService.getUsedSeconds(session.getTenantId(), session.getStudentUserId()));
            String guidance = "今天我们聊了不少啦，你已经很棒了。为了让眼睛和心情都休息一下，今天就先到这里好吗？"
                    + "明天我还在这里等你。\uD83C\uDF19 如果现在有紧急的事情，可以告诉老师，或拨打心理援助热线 12355。";
            return riskEvents.concatWith(Flux.just(
                    StreamMessageEvent.token(guidance),
                    StreamMessageEvent.done("")
            ));
        }

        // 5. 调用 AI 服务获取流式回复（CTX-Agent 结构化上下文 + 年级适配，PROF-010/011/012/015 + AI-008 + AI-005）
        boolean riskBlocked = fusedLevel != null && fusedLevel.severity() >= RiskLevel.ORANGE.severity();
        int effectiveGrade = ConversationUtils.computeEffectiveGrade(session.getGrade(), session.getExpressionDepth(), riskBlocked);
        String profilePrompt = profileService.buildProfilePrompt(session.getTenantId(), session.getStudentUserId(), session.getGrade(), session.getGender());
        // AI-008：长期记忆（跨会话关键事件回注）
        String memoryPrompt = longTermMemoryService.buildMemoryPrompt(session.getTenantId(), session.getStudentUserId());

        // ALLY-201/203：治疗联盟增强——连续性开场 + 中断回归照护（design/52 §五）
        String alliancePrompt = buildAlliancePrompt(session, memoryPrompt);

        // CTX-Agent：结构化上下文简报（身份+情绪旅程+会话进展+记忆+画像）
        int totalSessions = profileService.getSessionCount(session.getTenantId(), session.getStudentUserId());
        String contextBrief = contextAgent.buildContextBrief(session, profilePrompt, memoryPrompt, alliancePrompt, totalSessions);

        // AI-005：Prompt 版本 A/B 路由（DB 优先，classpath 降级）
        String gradeLevel = effectiveGrade <= 2 ? "1-2" : effectiveGrade <= 4 ? "3-4" : "5-6";
        PromptVersionService.ResolvedPrompt sysResolved = promptVersionService.resolve(
                session.getTenantId(), "SYS_001", session.getStudentUserId(), Map.of(
                        "grade_level", gradeLevel,
                        "emotion_tag", session.getEmotionTag() != null ? session.getEmotionTag() : "",
                        "school_policy", "默认：发现高风险立即通知心理老师。",
                        "session_mode", "normal_counseling"
                ));
        String langKey = effectiveGrade <= 2 ? "LANG_001" : effectiveGrade <= 4 ? "LANG_002" : "LANG_003";
        PromptVersionService.ResolvedPrompt langResolved = promptVersionService.resolveRaw(
                session.getTenantId(), langKey, session.getStudentUserId());
        String systemPromptContent = sysResolved.content() + "\n\n" + langResolved.content();

        // ORCH-001/002/003/005：编排引擎——先算策略、再拼提示词（design/44 §四/§七）。
        // VCL-001：轮级 currentEmotion 由语音 SER 映射驱动（置信门控 >0.6），
        // ORCH-003：状态机输入上一轮 state/reliefCount，输出转移结果存回 session。
        // ORCH-005：冷场 nudge 信号并入编排（nudgeCount>0 且本轮无学生输入时触发）。
        String currentEmotion = (voiceEmotion != null && voiceEmotionConfidence != null && voiceEmotionConfidence > 0.6)
                ? promptOrchestrationService.mapVoiceEmotion(voiceEmotion) : null;
        ProfileSignals profileSignals = profileService.getProfileSignals(session.getTenantId(), session.getStudentUserId());
        boolean nudgeActive = session.getNudgeCount() > 0;
        PromptOrchestrationService.Result orchResult = promptOrchestrationService.resolveWithTransition(
                new OrchestrationContext(session.getGrade(), effectiveGrade, session.getEmotionTag(),
                        currentEmotion, fusedLevel, profileSignals,
                        session.getEmotionState(), session.getReliefCount(), nudgeActive, session.isHighSensitivity()));
        StrategyProfile strategy = orchResult.profile();
        // 状态机转移结果存回会话（下一轮输入）
        session.setEmotionState(orchResult.transition().state());
        session.setReliefCount(orchResult.transition().reliefCount());
        PromptVersionService.ResolvedPrompt emoResolved = promptVersionService.resolve(
                session.getTenantId(), "EMO_001", session.getStudentUserId(),
                promptOrchestrationService.toTemplateVariables(strategy));
        systemPromptContent = systemPromptContent + "\n\n" + emoResolved.content();

        // CBT-201/202：CBT 阶段标记 + 年龄分层路由（design/52 §一，design/03 §11.3/11.4）
        CbtStageRouter.AgeStrategy ageStrategy = cbtStageRouter.resolveAgeStrategy(effectiveGrade);
        log.debug("CBT 年龄分层: sessionId={}, grade={}, strategy={}", sessionId, effectiveGrade, ageStrategy);

        // ALLY 连续性开场 / 回归照护已纳入 CTX-Agent contextBrief，不再单独注入

        // KB-101b：RAG 参考知识注入（design/49 §六）——场景触发才检索，寒暄闲聊不检索；
        // RED 危机场景已在 4.2 硬短路，不会走到此处；检索异常返回空串不影响主线。
        String ragContext = ragAdvisorService.buildRagContext(session.getTenantId(), safeContent, effectiveGrade);
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

        // Fix 3: ContextBrief 追加到 systemPromptContent 尾部（利用 recency bias，AI 最后读到 = 注意力最高）
        String finalSystemPrompt = systemPromptContent + "\n\n" + contextBrief;

        StringBuilder aiResponseCollector = new StringBuilder();
        Flux<StreamMessageEvent> aiStream = aiChatService.chatWithPrompt(sessionId, session.getEmotionTag(), safeContent, session.getGender(), null, effectiveGrade, finalSystemPrompt)
                .doOnNext(event -> {
                    if (event.type() != null && event.type().equals("token") && event.content() != null) {
                        aiResponseCollector.append(event.content());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    // AI 回复完成后持久化摘要
                    String fullReply = aiResponseCollector.toString();
                    messageSummaryService.persistAiMessageSummary(session, turn, fullReply);
                    // 冷场决策模型信号：AI 是否刚问了思考型问题
                    session.recordAiReply(fullReply);
                    sessionStateStore.save(sessionId, session);

                    // CTX-Agent Phase 3：每 4 轮异步更新滚动摘要（不阻塞当前轮响应）
                    if (sessionSummaryUpdater.shouldUpdate(session)) {
                        sessionSummaryUpdater.updateSummaryAsync(
                                session.getTenantId(), sessionId, session.getStudentUserId(), turn);
                    }

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
        SessionState session = sessionStateStore.get(sessionId);
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
                    sessionId, session.getNudgeCount());
            return Flux.empty();
        }

        // 冷场决策模型（design/28 §三 3.2）：多信号加权 → 留白/轻陪伴/引导破冰
        boolean riskBlocked = session.getMaxRiskSeverity() >= RiskLevel.ORANGE.severity();
        NudgeDecisionModel.NudgeDecision decision = nudgeDecisionModel.decide(new NudgeDecisionModel.NudgeContext(
                session.getEmotionTag(),
                silenceSeconds,
                session.getLastStudentMessageType(),
                session.isLastAiAskedThinkingQuestion(),
                session.getTurnCount(),
                riskBlocked,
                session.secondsSinceLastStudentMessage(),
                session.getExpressionDepth()
        ));

        // Enhancement 2: 情绪旅程约束——ACTIVATED/CRISIS 时强制轻陪伴，不引导破冰
        if (session.getEmotionState() != com.mindsafe.ai.orchestrator.StrategyProfile.EmotionState.STABLE
                && decision.warmthLevel() > 1) {
            decision = new NudgeDecisionModel.NudgeDecision(1, decision.direction());
            log.debug("nudge: 情绪状态机非 STABLE，warmthLevel 降级为 1: sessionId={}", sessionId);
        }
        // 连续积极回应 >= 3 时，暖场方向偏向积极肯定
        if (session.getReliefCount() >= 3 && decision.warmthLevel() > 0) {
            decision = new NudgeDecisionModel.NudgeDecision(decision.warmthLevel(), "积极肯定");
        }

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
        // Fix 1: 暖场接入 CTX-Agent（统一上下文简报，让暖场也知道昵称/情绪/进展）
        String profilePrompt = profileService.buildProfilePrompt(session.getTenantId(), session.getStudentUserId(), session.getGrade(), session.getGender());
        String nudgeMemoryPrompt = longTermMemoryService.buildMemoryPrompt(session.getTenantId(), session.getStudentUserId());
        String nudgeAlliancePrompt = buildAlliancePrompt(session, nudgeMemoryPrompt);
        int nudgeTotalSessions = profileService.getSessionCount(session.getTenantId(), session.getStudentUserId());
        String nudgeContextBrief = contextAgent.buildContextBrief(session, profilePrompt, nudgeMemoryPrompt, nudgeAlliancePrompt, nudgeTotalSessions);
        // PROF-015：暖场场景无风险（橙/红已拦截），仅根据表达深度降级
        int effectiveGrade = ConversationUtils.computeEffectiveGrade(session.getGrade(), session.getExpressionDepth(), false);
        int turn = session.getTurnCount();
        StringBuilder aiResponseCollector = new StringBuilder();

        session.markNudged();
        sessionStateStore.save(sessionId, session);
        log.info("nudge: 决策=暖场: sessionId={}, warmthLevel={}, direction={}, silenceSeconds={}",
                sessionId, decision.warmthLevel(), decision.direction(), silenceSeconds);

        return aiChatService.chatProactive(sessionId, session.getEmotionTag(), session.getGender(), nudgeContextBrief, nudgeInstruction, effectiveGrade)
                .doOnNext(event -> {
                    if (event.type() != null && event.type().equals("token") && event.content() != null) {
                        aiResponseCollector.append(event.content());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String fullReply = aiResponseCollector.toString();
                    messageSummaryService.persistAiMessageSummary(session, turn, fullReply);
                    session.recordAiReply(fullReply);
                    sessionStateStore.save(sessionId, session);
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
        SessionState session = sessionStateStore.get(sessionId);
        if (session != null) {
            sessionStateStore.remove(sessionId);

            // 更新 DB 会话状态 + 轮次数
            CounselingSession update = new CounselingSession();
            update.setSessionId(sessionId);
            update.setEndedAt(Instant.now());
            update.setSessionStatus("completed");
            update.setTurnCount(session.getTurnCount());
            update.setUpdatedAt(Instant.now());
            sessionMapper.updateById(update);

            // 清除 AI 对话记忆
            aiChatService.clearMemory(sessionId);
            log.info("会话结束: sessionId={}, turns={}", sessionId, session.getTurnCount());

            // AB-002：采集会话深度指标（异步聚合，不阻塞主流程）
            try {
                ExperimentMetricsCollector.MetricEvent depthEvent = new ExperimentMetricsCollector.MetricEvent(
                        "default_exp", "CONTROL", session.getStudentUserId().toString(),
                        ExperimentMetricsCollector.MetricType.SESSION_DEPTH,
                        session.getTurnCount(), java.time.LocalDate.now());
                log.debug("AB 指标采集: sessionId={}, metric=SESSION_DEPTH, value={}", sessionId, depthEvent.value());
            } catch (Exception e) {
                log.debug("AB 指标采集失败（不影响业务）: {}", e.getMessage());
            }

            // 异步生成 AI 会话摘要（摘要完成后触发 PROF-003 画像 LLM 提炼）
            messageSummaryService.generateSummaryAsync(tenantId, sessionId, session.getStudentUserId());

            // 异步更新学生画像（基于历史会话统计；VCL-001：本会话语音情绪聚合回注 emotionBaseline.voice，
            // 只传可映射到规范集的标签，聚合衍生特征不留逐条流水，design/47 §5.1）
            List<String> voiceEmotions = session.emotionLabels().stream()
                    .map(promptOrchestrationService::mapVoiceEmotion)
                    .filter(Objects::nonNull)
                    .toList();
            profileService.updateProfile(session.getTenantId(), session.getStudentUserId(), voiceEmotions);

            // RISK-204 / ORCH-008 / PROF-024：会话结束分析（趋势+效果量化，异步不阻塞）
            try {
                sessionEndAnalyticsService.analyze(
                        session.getTenantId(), session.getStudentUserId(),
                        voiceEmotions, // 近期情绪（当前仅本会话，后续扩展跨会话查询）
                        session.emotionLabels(),
                        List.of(), // studentMessages 待后续从 DB 补充
                        session.getEmotionTag());
            } catch (Exception e) {
                log.debug("会话结束分析降级: {}", e.getMessage());
            }
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
     * CTX-Agent Phase 5：主题线索提取（轻量规则，零 LLM）。
     * <p>
     * 从学生消息中提取主题关键词存入 SessionState.topicHints：
     * - 风险类别（已有分类，如 self_harm/bullying/family_conflict）
     * - 简单关键词匹配（同学/老师/妈妈/考试/朋友等高频主题）
     */
    private void extractTopicHint(SessionState session, String content, RiskDetectionResult riskResult, int turn) {
        try {
            // 1. 风险类别作为主题（已有分类，高价值）
            if (riskResult != null && riskResult.isRisky() && riskResult.category() != null) {
                session.addTopicHint(riskResult.category(), turn);
            }

            // 2. 简单关键词提取（小学生高频话题）
            if (content == null || content.length() < 4) return;
            String[][] topicPatterns = {
                    {"同学", "同学关系"}, {"朋友", "友谊"}, {"妈妈", "和妈妈的关系"},
                    {"爸爸", "和爸爸的关系"}, {"老师", "和老师的关系"},
                    {"考试", "考试压力"}, {"成绩", "学习压力"}, {"作业", "学习压力"},
                    {"欺负", "被欺负"}, {"打我", "被欺负"}, {"骂我", "被欺负"},
                    {"不想活", "自伤倾向"}, {"死", "自伤倾向"},
                    {"孤独", "孤独感"}, {"没人", "孤独感"},
                    {"害怕", "恐惧"}, {"担心", "焦虑"},
                    {"生气", "愤怒"}, {"讨厌", "厌恶"},
                    {"弟弟", "兄弟姐妹关系"}, {"妹妹", "兄弟姐妹关系"},
            };
            for (String[] pattern : topicPatterns) {
                if (content.contains(pattern[0])) {
                    session.addTopicHint(pattern[1], turn);
                    break; // 每轮最多提取 1 个关键词主题（避免噪音）
                }
            }
        } catch (Exception e) {
            log.debug("CTX-Agent 主题提取失败（不影响对话）: {}", e.getMessage());
        }
    }
}
