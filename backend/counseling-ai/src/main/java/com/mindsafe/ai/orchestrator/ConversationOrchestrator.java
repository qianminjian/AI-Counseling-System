package com.mindsafe.ai.orchestrator;

import com.mindsafe.ai.agent.*;
import com.mindsafe.ai.safety.CrisisResources;
import com.mindsafe.ai.state.CbtState;
import com.mindsafe.ai.state.CbtStateMachine;
import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对话编排器（对齐 design/13 §4.2 编排流程）
 * <p>
 * 编排流程：
 * START → SafetyAgent.check()
 *   ├─ unsafe(L3+) → 安全话术直接返回（L4/L5 用硬编码模板）
 *   └─ safe → EmotionAgent.recognize()
 *       ├─ high_intensity(>=7) → CBTAgent.intervene()
 *       └─ low_intensity(<7)  → ConversationAgent.reply()
 * → CbtStateMachine.evaluate() → END（返回 OrchestratorResult）
 * <p>
 * 当前为同步编排（Phase 1.3），后续优化为虚拟线程并行 Safety+Emotion。
 */
@Service
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);

    private final SafetyAgent safetyAgent;
    private final EmotionAgent emotionAgent;
    private final CBTAgent cbtAgent;
    private final ConversationAgent conversationAgent;
    private final CbtStateMachine cbtStateMachine;

    public ConversationOrchestrator(SafetyAgent safetyAgent,
                                    EmotionAgent emotionAgent,
                                    CBTAgent cbtAgent,
                                    ConversationAgent conversationAgent,
                                    CbtStateMachine cbtStateMachine) {
        this.safetyAgent = safetyAgent;
        this.emotionAgent = emotionAgent;
        this.cbtAgent = cbtAgent;
        this.conversationAgent = conversationAgent;
        this.cbtStateMachine = cbtStateMachine;
    }

    /**
     * 编排一次完整的对话请求
     */
    public OrchestratorResult orchestrate(String message, ConversationContext context) {
        log.debug("编排开始: sessionId={}, turn={}, cbtState={}",
                context.sessionId(), context.turnCount(), context.currentCbtState());

        // ===== 1. Safety Agent（输入侧安全检测）=====
        SafetyAgent.Result safetyResult = safetyAgent.execute(
                new SafetyAgent.Input(message, null, null), context);

        // 高风险短路：L4/L5 直接返回硬编码安全话术，不经过 LLM 生成
        if (safetyResult.isRisky() && safetyResult.needsImmediateEscalation()) {
            String safetyReply = safetyResult.riskLevel() == RiskLevel.RED
                    ? CrisisResources.L5_SAFETY_REPLY
                    : CrisisResources.L4_SAFETY_REPLY;
            log.warn("编排短路-高风险安全话术: level={}, source={}",
                    safetyResult.riskLevel(), safetyResult.detectionSource());
            return OrchestratorResult.escalated(safetyReply, safetyResult);
        }

        // ===== 2. Emotion Agent（情绪识别）=====
        EmotionAgent.Result emotionResult = emotionAgent.execute(
                new EmotionAgent.Input(message), context);

        log.debug("情绪识别: emotion={}, intensity={}, trend={}",
                emotionResult.primaryEmotion(), emotionResult.intensity(), emotionResult.trend());

        // ===== 3. 路由决策 =====
        // 超轮次 → 收束
        if (context.isTurnLimitReached()) {
            log.info("轮次上限到达，进入收束: turn={}", context.turnCount());
            return OrchestratorResult.closing(
                    "我们今天先聊到这里。你已经做得很好了。如果还想继续，明天可以再来。也可以找老师聊一聊。",
                    safetyResult, emotionResult, context.currentCbtState());
        }

        String reply;
        String nextCbtState = context.currentCbtState();
        String route;

        if (emotionResult.isHighIntensity() || isCbtActive(context.currentCbtState())) {
            // ===== 4a. CBT Agent（高强度/已激活 CBT 流程）=====
            route = "cbt";
            CBTAgent.Result cbtResult = cbtAgent.execute(
                    new CBTAgent.Input(
                            message,
                            context.currentCbtState() != null ? context.currentCbtState() : "S2_EMOTION_LABEL",
                            null,
                            emotionResult.primaryEmotion(),
                            emotionResult.intensity(),
                            null,
                            null
                    ), context);
            reply = cbtResult.reply();
            nextCbtState = cbtResult.nextState();
        } else {
            // ===== 4b. Conversation Agent（低强度支持性回复）=====
            route = "conversation";
            ConversationAgent.Result convResult = conversationAgent.execute(
                    new ConversationAgent.Input(message), context);
            reply = convResult.reply();
        }

        log.debug("编排完成: route={}, replyLength={}, nextState={}",
                route, reply.length(), nextCbtState);

        return new OrchestratorResult(
                reply, safetyResult, emotionResult,
                nextCbtState, route, context.remainingTurns(), false);
    }

    /** CBT 流程是否已激活（非初始/非情绪命名状态） */
    private boolean isCbtActive(String cbtState) {
        CbtState state = CbtState.fromString(cbtState);
        return state.isInterventionActive()
                || state == CbtState.S3_SCENARIO_ROUTE
                || state == CbtState.S8_RECHECK_CLOSE;
    }

    // ===== 编排结果 =====

    public record OrchestratorResult(
            String reply,
            SafetyAgent.Result safetyResult,
            EmotionAgent.Result emotionResult,
            String nextCbtState,
            String route,
            int remainingTurns,
            boolean escalated
    ) {
        public static OrchestratorResult escalated(String reply, SafetyAgent.Result safety) {
            return new OrchestratorResult(reply, safety, null, "ESCALATING", "safety_shortcut", 0, true);
        }

        public static OrchestratorResult closing(String reply, SafetyAgent.Result safety,
                                                  EmotionAgent.Result emotion, String cbtState) {
            return new OrchestratorResult(reply, safety, emotion, cbtState, "closing", 0, false);
        }

        public RiskLevel riskLevel() {
            return safetyResult != null ? safetyResult.riskLevel() : null;
        }
    }
}
