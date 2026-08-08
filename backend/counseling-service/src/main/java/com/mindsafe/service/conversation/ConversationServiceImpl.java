package com.mindsafe.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindsafe.ai.ally.AllianceEnhancer;
import com.mindsafe.ai.cbt.CbtStageRouter;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.ai.orchestrator.OrchestrationContext;
import com.mindsafe.ai.orchestrator.ProfileSignals;
import com.mindsafe.ai.orchestrator.PromptOrchestrationService;
import com.mindsafe.ai.orchestrator.ReplyEmotionResolver;
import com.mindsafe.ai.orchestrator.StrategyProfile;
import com.mindsafe.ai.safety.ConfidentialityNotice;
import com.mindsafe.ai.safety.CrisisResourceProvider;
import com.mindsafe.ai.safety.HighSensitivityCategories;
import com.mindsafe.ai.safety.PiiDesensitizer;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.dto.chat.SessionInfo;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.knowledge.RagAdvisorService;
import com.mindsafe.service.memory.LongTermMemoryService;
import com.mindsafe.service.memory.ThemeEvolutionEngine;
import com.mindsafe.service.profile.StudentProfileService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.conversation.strategy.NudgeStrategy;
import com.mindsafe.service.conversation.strategy.RiskResponseStrategy;
import com.mindsafe.service.usage.UsageTimeLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
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
    private final ConversationRiskProcessor riskProcessor;
    private final PiiDesensitizer piiDesensitizer;
    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    private final UserMapper userMapper;
    private final StudentProfileService profileService;
    private final LongTermMemoryService longTermMemoryService;
    private final UsageTimeLimitService usageTimeLimitService;
    private final RagAdvisorService ragAdvisorService;
    private final PromptOrchestrationService promptOrchestrationService;
    private final MessageSummaryService messageSummaryService;
    private final FieldEncryptionService fieldEncryptionService;
    private final CrisisResourceProvider crisisResourceProvider;
    private final AllianceEnhancer allianceEnhancer;
    private final CbtStageRouter cbtStageRouter;
    private final SessionEndAnalyticsService sessionEndAnalyticsService;
    private final RedisSessionStateStore sessionStateStore;
    private final ConversationContextAgent contextAgent;
    private final SessionSummaryUpdater sessionSummaryUpdater;

    /** 会话级个人信息提取器（纯正则，ARCH-001 C1 拆分） */
    private final PersonalInfoExtractor personalInfoExtractor;

    /** Prompt 组装服务（版本路由 + 固定顺序拼接，ARCH-001 C1 拆分） */
    private final PromptAssemblyService promptAssemblyService;

    /** 主题演化引擎（话题关键词表单一源，ARCH-001 C1 收敛） */
    private final ThemeEvolutionEngine themeEvolutionEngine;

    /** 暖场护栏配置（B2：阈值单一源，Lua 判定与快照判定同源） */
    private final NudgeProperties nudgeProperties;

    /** 冷场决策模型（无状态纯计算，design/28 §三） */
    private final NudgeDecisionModel nudgeDecisionModel = new NudgeDecisionModel();

    /** 回复情绪推导器（TTSFX-004，design/37 §三.1）：纯规则零依赖，同 NudgeDecisionModel 内联实例化 */
    private final ReplyEmotionResolver replyEmotionResolver = new ReplyEmotionResolver();

    /** CBT state_path JSON 序列化工具（CBT-201）；ARCH-010 P2-2：注入唯一 ObjectMapper（此前 static new） */
    private final ObjectMapper objectMapper;

    public ConversationServiceImpl(AiChatService aiChatService,
                                   ConversationRiskProcessor riskProcessor,
                                   PiiDesensitizer piiDesensitizer,
                                   CounselingSessionMapper sessionMapper,
                                   MessageSummaryMapper messageSummaryMapper,
                                   UserMapper userMapper,
                                   StudentProfileService profileService,
                                   UsageTimeLimitService usageTimeLimitService,
                                   LongTermMemoryService longTermMemoryService,
                                   RagAdvisorService ragAdvisorService,
                                   PromptOrchestrationService promptOrchestrationService,
                                   MessageSummaryService messageSummaryService,
                                   FieldEncryptionService fieldEncryptionService,
                                   CrisisResourceProvider crisisResourceProvider,
                                   AllianceEnhancer allianceEnhancer,
                                   CbtStageRouter cbtStageRouter,
                                   SessionEndAnalyticsService sessionEndAnalyticsService,
                                   RedisSessionStateStore sessionStateStore,
                                   ConversationContextAgent contextAgent,
                                   SessionSummaryUpdater sessionSummaryUpdater,
                                   ObjectMapper objectMapper,
                                   PersonalInfoExtractor personalInfoExtractor,
                                   PromptAssemblyService promptAssemblyService,
                                   ThemeEvolutionEngine themeEvolutionEngine,
                                   NudgeProperties nudgeProperties) {
        this.aiChatService = aiChatService;
        this.riskProcessor = riskProcessor;
        this.piiDesensitizer = piiDesensitizer;
        this.sessionMapper = sessionMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.userMapper = userMapper;
        this.profileService = profileService;
        this.usageTimeLimitService = usageTimeLimitService;
        this.longTermMemoryService = longTermMemoryService;
        this.ragAdvisorService = ragAdvisorService;
        this.promptOrchestrationService = promptOrchestrationService;
        this.messageSummaryService = messageSummaryService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.crisisResourceProvider = crisisResourceProvider;
        this.allianceEnhancer = allianceEnhancer;
        this.cbtStageRouter = cbtStageRouter;
        this.sessionEndAnalyticsService = sessionEndAnalyticsService;
        this.sessionStateStore = sessionStateStore;
        this.contextAgent = contextAgent;
        this.sessionSummaryUpdater = sessionSummaryUpdater;
        this.objectMapper = objectMapper;
        this.personalInfoExtractor = personalInfoExtractor;
        this.promptAssemblyService = promptAssemblyService;
        this.themeEvolutionEngine = themeEvolutionEngine;
        this.nudgeProperties = nudgeProperties;
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

        SessionState newState = new SessionState(
                sessionId, tenantId, studentUserId, emotionTag, channel, gender, expressionDepth, grade);
        newState.setPseudonym(pseudonym);  // CTX-Agent：身份简报用
        sessionStateStore.save(tenantId, sessionId, newState);
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
    public Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID studentUserId, UUID sessionId, String content) {
        return sendMessageStream(tenantId, studentUserId, sessionId, content, null, null);
    }

    @Override
    public Flux<StreamMessageEvent> sendMessageStream(UUID tenantId, UUID studentUserId, UUID sessionId, String content,
                                                      String voiceEmotion, Double voiceEmotionConfidence) {
        SessionState session = sessionStateStore.get(tenantId, sessionId);
        if (session == null) {
            return Flux.just(StreamMessageEvent.error("会话不存在"));
        }
        // SEC-001：会话归属校验——拦截跨租户/跨学生的会话劫持（同校学生拿到他人 sessionId 不可注入消息）
        if (!isSessionOwner(session, tenantId, studentUserId)) {
            log.warn("会话归属校验失败，拒绝消息: sessionId={}, tenantId={}, userId={}", sessionId, tenantId, studentUserId);
            return Flux.just(StreamMessageEvent.error("会话不存在"));
        }

        int turn = session.incrementTurnCount();
        log.debug("收到消息: sessionId={}, turn={}, length={}, voiceEmotion={}",
                sessionId, turn, content.length(), voiceEmotion);

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
        // T5：nudge 计数原子清零（真值在 Redis 独立键，防与暖场定时路径并发写丢失更新）
        sessionStateStore.resetNudgeCounter(tenantId, sessionId);
        if (fusedLevel != null) {
            session.updateMaxRiskSeverity(fusedLevel.severity());
        }

        // 4.1b CTX-Agent Phase 5：主题线索提取（轻量规则，零 LLM）
        extractTopicHint(session, content, riskResult, turn);

        // 4.1c CTX-Agent：会话级个人信息提取（轻量规则，零 LLM，会话结束即销毁；ARCH-001 C1 收敛 PersonalInfoExtractor）
        PersonalInfoExtractor.ExtractedInfo extracted = personalInfoExtractor.extract(content);
        if (extracted != null) {
            if (extracted.realName() != null) session.updatePersonalInfo("realName", extracted.realName());
            if (extracted.age() != null) session.updatePersonalInfo("age", extracted.age());
            if (extracted.grade() != null) session.updatePersonalInfo("grade", extracted.grade());
            if (extracted.className() != null) session.updatePersonalInfo("class", extracted.className());
        }

        // 持久化本轮状态变更（覆盖 RED 短路 / 时长超限等提前返回路径）
        sessionStateStore.save(tenantId, sessionId, session);

        // 4.2 RISK-201：RED 硬短路——跳过 LLM 自由生成，返回预审核安全文案（design/04 §18.2）。
        //     短路不可被否定/引用降噪覆盖（fusedLevel 已经硬规则融合）；教师告警已在上方照发。
        //     安全响应模式：RED 触发后的后续轮次也不再自由生成，返回陪伴话术，解除需教师处置/新会话。
        //     （DC-010：策略决策下沉 RiskResponseStrategy）
        if (fusedLevel == RiskLevel.RED || session.inSafetyMode()) {
            String safetyReply = RiskResponseStrategy.resolveSafetyReply(
                    fusedLevel, session.inSafetyMode(), session.getGrade(), crisisResourceProvider);
            messageSummaryService.persistAiMessageSummary(session, turn, safetyReply);
            session.recordAiReply(safetyReply);
            sessionStateStore.save(tenantId, sessionId, session);
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
            String guidance = RiskResponseStrategy.buildTimeLimitGuidance(crisisResourceProvider.getHotlineNumber());
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

        // AI-005 + ARCH-010 D4：Prompt 版本路由与固定顺序组装，收敛 PromptAssemblyService（ARCH-001 C1）
        // CBT-201/202 + WIRE-002：阶段推断 + 年龄分层标记 → 指令注入 + state_path 落库（design/03 §11.3/11.4）
        CbtStageRouter.CbtStage cbtStage = cbtStageRouter.inferStage(turn, session.getEmotionState());
        boolean allowCbt = session.getEmotionState() == StrategyProfile.EmotionState.STABLE;
        CbtStageRouter.StageMark stageMark = cbtStageRouter.mark(cbtStage, effectiveGrade, allowCbt);

        // KB-101b：RAG 参考知识注入（design/49 §六）——场景触发才检索，寒暄闲聊不检索；
        // RED 危机场景已在 4.2 硬短路，不会走到此处；检索异常返回空串不影响主线。
        String ragContext = ragAdvisorService.buildRagContext(session.getTenantId(), safeContent, effectiveGrade);
        if (!ragContext.isEmpty()) {
            log.info("RAG 参考知识已注入: sessionId={}, contextLen={}", sessionId, ragContext.length());
        }
        PromptAssemblyService.AssembledPrompt assembled = promptAssemblyService.assembleMainPrompt(
                session.getTenantId(), session.getStudentUserId(), effectiveGrade, session.getEmotionTag(),
                promptOrchestrationService.toTemplateVariables(strategy), stageMark, ragContext, session.getGender());
        String systemPromptContent = assembled.content();

        CounselingSession dbSession = sessionMapper.selectById(sessionId);
        String statePath = appendStatePath(dbSession != null ? dbSession.getStatePath() : null, turn, stageMark);
        log.debug("CBT 阶段标记: sessionId={}, turn={}, stage={}, strategy={}, allowCbt={}",
                sessionId, turn, cbtStage, stageMark.ageStrategy(), allowCbt);

        // ALLY 连续性开场 / 回归照护已纳入 CTX-Agent contextBrief，不再单独注入

        // 记录 Prompt 版本与 CBT state_path 到会话（A/B 对比 + design/45 评估闭环数据源）
        String versionTag = assembled.versionTag();
        CounselingSession versionUpdate = new CounselingSession();
        versionUpdate.setSessionId(sessionId);
        versionUpdate.setPromptVersion(versionTag);
        versionUpdate.setStatePath(statePath);
        versionUpdate.setUpdatedAt(Instant.now());
        sessionMapper.updateById(versionUpdate);

        // Fix 3: ContextBrief 追加到 systemPromptContent 尾部（利用 recency bias，AI 最后读到 = 注意力最高）
        String finalSystemPrompt = systemPromptContent + "\n\n" + contextBrief;

        StringBuilder aiResponseCollector = new StringBuilder();
        Flux<StreamMessageEvent> aiStream = aiChatService.chatWithPrompt(sessionId, session.getEmotionTag(), safeContent, finalSystemPrompt)
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
                    sessionStateStore.save(tenantId, sessionId, session);

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

        // 6. 组合：风险事件 + 回复情绪事件（TTSFX-004：波波表情状态机需在语音开播前收到信号，design/37 M1） + AI 回复
        ReplyEmotionResolver.Result replyEmotion = replyEmotionResolver.resolve(strategy);
        Flux<StreamMessageEvent> emotionEvent = Flux.just(
                StreamMessageEvent.emotion(replyEmotion.emotion(), replyEmotion.intensity()));
        return riskEvents.concatWith(emotionEvent).concatWith(aiStream);
    }

    @Override
    public Flux<StreamMessageEvent> sendNudgeStream(UUID tenantId, UUID studentUserId, UUID sessionId, int silenceSeconds) {
        SessionState session = sessionStateStore.get(tenantId, sessionId);
        if (session == null) {
            log.debug("nudge: 会话不存在，返回空流: sessionId={}", sessionId);
            return Flux.empty();
        }
        // SEC-001：会话归属校验
        if (!isSessionOwner(session, tenantId, studentUserId)) {
            log.warn("nudge: 会话归属校验失败，返回空流: sessionId={}, tenantId={}, userId={}", sessionId, tenantId, studentUserId);
            return Flux.empty();
        }

        // 护栏 1：会话已 escalated（红色风险）→ 不做日常暖场（安全流程接管）
        if (isSessionEscalated(sessionId)) {
            log.debug("nudge: 会话已 escalated，返回空流: sessionId={}", sessionId);
            return Flux.empty();
        }

        // 护栏 2：连续暖场次数（≤上限）/ 间隔（≥最小间隔），孩子说话即清零（B2：阈值取 NudgeProperties 单一配置源）
        if (!session.canNudge(nudgeProperties.getMaxCount(), nudgeProperties.getMinIntervalSeconds())) {
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

        // Enhancement 2: 情绪旅程约束——ACTIVATED/CRISIS 时强制轻陪伴，不引导破冰；
        // 连续积极回应 >= 3 时方向偏积极肯定（DC-010：策略决策下沉 NudgeStrategy）
        decision = NudgeStrategy.adjust(decision, session.getEmotionState(), session.getReliefCount());

        if (decision.warmthLevel() == 0) {
            // 留白：把安静还给孩子（他可能在思考），前端不做任何事
            log.debug("nudge: 决策=留白，返回空流: sessionId={}, silenceSeconds={}", sessionId, silenceSeconds);
            return Flux.empty();
        }

        // 暖场：TSK_004 指令路由与组装收敛 PromptAssemblyService（ARCH-010 D4 + ARCH-001 C1，与主链路同一加载路径）
        // 暖场上下文简报（CTX-Agent 统一上下文，同主链路组装）
        String profilePrompt = profileService.buildProfilePrompt(session.getTenantId(), session.getStudentUserId(), session.getGrade(), session.getGender());
        String nudgeMemoryPrompt = longTermMemoryService.buildMemoryPrompt(session.getTenantId(), session.getStudentUserId());
        String nudgeAlliancePrompt = buildAlliancePrompt(session, nudgeMemoryPrompt);
        int nudgeTotalSessions = profileService.getSessionCount(session.getTenantId(), session.getStudentUserId());
        String nudgeContextBrief = contextAgent.buildContextBrief(session, profilePrompt, nudgeMemoryPrompt, nudgeAlliancePrompt, nudgeTotalSessions);
        // PROF-015：暖场场景无风险（橙/红已拦截），仅根据表达深度降级
        int effectiveGrade = ConversationUtils.computeEffectiveGrade(session.getGrade(), session.getExpressionDepth(), false);
        int turn = session.getTurnCount();
        StringBuilder aiResponseCollector = new StringBuilder();

        // ARCH-010 D4：SYS_001 + 语言模板 + TSK_004 与主链路同一版本路由，组装收敛 PromptAssemblyService（ARCH-001 C1）；
        // contextBrief 由 chatProactive 追加到 system 层尾部（recency bias），此处不拼入
        String systemPromptContent = promptAssemblyService.assembleNudgePrompt(
                session.getTenantId(), session.getStudentUserId(), effectiveGrade,
                session.getEmotionTag(), Map.of(
                        "silence_seconds", String.valueOf(silenceSeconds),
                        "warmth_level", String.valueOf(decision.warmthLevel()),
                        "direction", decision.direction()
                ), session.getGender());

        // T5：原子护栏放行 + 计数（Redis 独立键 Lua；并发下防双发/丢失更新，真值以 Lua 判定为准）
        if (!sessionStateStore.tryNudge(tenantId, sessionId)) {
            log.debug("nudge: 原子护栏拦截（并发暖场/计数超限），放弃本次: sessionId={}", sessionId);
            return Flux.empty();
        }
        session.markNudged();
        sessionStateStore.save(tenantId, sessionId, session);
        log.info("nudge: 决策=暖场: sessionId={}, warmthLevel={}, direction={}, silenceSeconds={}",
                sessionId, decision.warmthLevel(), decision.direction(), silenceSeconds);

        return aiChatService.chatProactive(sessionId, session.getEmotionTag(), nudgeContextBrief, systemPromptContent)
                .doOnNext(event -> {
                    if (event.type() != null && event.type().equals("token") && event.content() != null) {
                        aiResponseCollector.append(event.content());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String fullReply = aiResponseCollector.toString();
                    messageSummaryService.persistAiMessageSummary(session, turn, fullReply);
                    session.recordAiReply(fullReply);
                    sessionStateStore.save(tenantId, sessionId, session);
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
    public void updateClientSettings(UUID tenantId, UUID studentUserId, UUID sessionId, Boolean ttsMuted, Boolean wakeEnabled) {
        SessionState session = sessionStateStore.get(tenantId, sessionId);
        if (session == null) return;
        // SEC-001：会话归属校验（非持有人静默忽略，不泄漏会话存在性）
        if (!isSessionOwner(session, tenantId, studentUserId)) {
            log.warn("updateClientSettings: 会话归属校验失败，忽略: sessionId={}, tenantId={}, userId={}", sessionId, tenantId, studentUserId);
            return;
        }
        if (ttsMuted != null) session.setTtsMuted(ttsMuted);
        if (wakeEnabled != null) session.setWakeEnabled(wakeEnabled);
        sessionStateStore.save(tenantId, sessionId, session);
    }

    /**
     * SEC-001：会话归属校验——调用方租户与学生身份必须与会话状态完全匹配。
     * Redis 面不受租户拦截器保护，此校验是防跨会话劫持的唯一防线。
     */
    private boolean isSessionOwner(SessionState session, UUID tenantId, UUID studentUserId) {
        return java.util.Objects.equals(session.getTenantId(), tenantId)
                && java.util.Objects.equals(session.getStudentUserId(), studentUserId);
    }

    @Transactional
    @Override
    public void endSession(UUID tenantId, UUID studentUserId, UUID sessionId) {
        SessionState session = sessionStateStore.get(tenantId, sessionId);
        if (session != null) {
            // SEC-001：非持有人拒绝结束他人会话
            if (!isSessionOwner(session, tenantId, studentUserId)) {
                log.warn("endSession: 会话归属校验失败，拒绝: sessionId={}, tenantId={}, userId={}", sessionId, tenantId, studentUserId);
                throw new BizException(ErrorCode.FORBIDDEN);
            }

            // 先落库再删缓存：DB 写失败时 Redis 会话状态不丢失（fix-tx）
            CounselingSession update = new CounselingSession();
            update.setSessionId(sessionId);
            update.setEndedAt(Instant.now());
            update.setSessionStatus(CounselingSession.STATUS_COMPLETED);
            update.setTurnCount(session.getTurnCount());
            update.setUpdatedAt(Instant.now());
            sessionMapper.updateById(update);

            sessionStateStore.remove(tenantId, sessionId);

            // 清除 AI 对话记忆
            aiChatService.clearMemory(sessionId);
            log.info("会话结束: sessionId={}, turns={}", sessionId, session.getTurnCount());

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
                        loadStudentMessages(tenantId, sessionId),
                        session.getEmotionTag());
            } catch (Exception e) {
                log.debug("会话结束分析降级: {}", e.getMessage());
            }
        }
    }

    @Override
    public List<CounselingSession> getSessionHistory(UUID tenantId, UUID studentUserId, int limit) {
        // T4 批次C：租户+学生双重条件内置（原 SessionController 直查 selectPage 下沉）
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<CounselingSession> pageResult =
                sessionMapper.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, Math.min(limit, 50), false),
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CounselingSession>()
                                .eq(CounselingSession::getTenantId, tenantId)
                                .eq(CounselingSession::getStudentUserId, studentUserId)
                                .orderByDesc(CounselingSession::getStartedAt)
                );
        return pageResult.getRecords();
    }

    @Transactional
    @Override
    public void rateSession(UUID tenantId, UUID studentUserId, UUID sessionId, int rating, String comment) {
        // SEC-001：归属校验（租户+学生双重条件，非持有人拒绝评价）——T4 批次B 下沉
        CounselingSession existing = sessionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .eq(CounselingSession::getSessionId, sessionId)
        );
        if (existing == null) {
            log.warn("rateSession: 会话归属校验失败，拒绝: sessionId={}, tenantId={}, userId={}", sessionId, tenantId, studentUserId);
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        CounselingSession update = new CounselingSession();
        update.setSessionId(sessionId);
        update.setSatisfactionRating(rating);
        update.setSatisfactionComment(comment);
        update.setUpdatedAt(Instant.now());
        sessionMapper.updateById(update);
    }

    /**
     * AUDIT-P2-20：加载本会话学生消息明文列表，供 ORCH-008 会话深度量化使用。
     * <p>
     * 修复前调用方传 {@code List.of()} 占位，measureDepth 恒为 0，深度量化形同虚设。
     * 消息摘要逐轮同步落库（见 MessageSummaryService.persistStudentMessageSummary），
     * 会话结束时 DB 中数据已完整，此处查询并解密即可；失败降级为空列表（分析本身异步可降级）。
     */
    private List<String> loadStudentMessages(UUID tenantId, UUID sessionId) {
        try {
            List<MessageSummary> messages = messageSummaryMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageSummary>()
                            .eq(MessageSummary::getTenantId, tenantId)
                            .eq(MessageSummary::getSessionId, sessionId)
                            .eq(MessageSummary::getSenderType, User.USER_TYPE_STUDENT)
                            .orderByAsc(MessageSummary::getTurnCount)
                            .orderByAsc(MessageSummary::getCreatedAt)
            );
            if (messages.isEmpty()) {
                return List.of();
            }
            List<String> texts = new ArrayList<>(messages.size());
            for (MessageSummary m : messages) {
                String plain = fieldEncryptionService.decrypt(m.getContentSummary());
                if (plain != null && !plain.isBlank()) {
                    texts.add(plain);
                }
            }
            return texts;
        } catch (Exception e) {
            log.debug("会话结束分析加载学生消息失败，降级为空列表: {}", e.getMessage());
            return List.of();
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

            // 2. 简单关键词提取（关键词表收敛 ThemeEvolutionEngine 单一源，ARCH-001 C1；每轮最多 1 个）
            String topic = themeEvolutionEngine.findTopicHint(content);
            if (topic != null) {
                session.addTopicHint(topic, turn);
            }
        } catch (Exception e) {
            log.debug("CTX-Agent 主题提取失败（不影响对话）: {}", e.getMessage());
        }
    }

    /**
     * 追加 CBT state_path 阶段标记（CBT-201，design/03 §11.4）。
     * <p>
     * state_path 为 jsonb 数组，每轮追加一条 {turn,stage,age_strategy,allowed_techniques,allow_cbt}。
     * 复用现有会话表 JSONB 列，无 schema 变更；敏感原文不入标记（§六隐私策略）。
     * 解析/序列化失败时降级为本轮单条记录，不阻断主流程。
     */
    private String appendStatePath(String existingJson, int turn, CbtStageRouter.StageMark mark) {
        try {
            ObjectNode record = objectMapper.createObjectNode();
            record.put("turn", turn);
            record.put("stage", mark.stage().name());
            record.put("age_strategy", mark.ageStrategy().name());
            ArrayNode techniques = record.putArray("allowed_techniques");
            mark.allowedTechniques().forEach(techniques::add);
            record.put("allow_cbt", mark.allowCbt());

            ArrayNode arr;
            if (existingJson == null || existingJson.isBlank()) {
                arr = objectMapper.createArrayNode();
            } else {
                var parsed = objectMapper.readTree(existingJson);
                arr = parsed.isArray() ? (ArrayNode) parsed : objectMapper.createArrayNode();
            }
            arr.add(record);
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("state_path 序列化失败，降级为本轮单条记录: {}", e.getMessage());
            return String.format(
                    "[{\"turn\":%d,\"stage\":\"%s\",\"age_strategy\":\"%s\",\"allow_cbt\":%b}]",
                    turn, mark.stage().name(), mark.ageStrategy().name(), mark.allowCbt());
        }
    }
}
