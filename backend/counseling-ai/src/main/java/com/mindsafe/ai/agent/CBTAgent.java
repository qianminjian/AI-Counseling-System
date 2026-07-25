package com.mindsafe.ai.agent;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.state.CbtState;
import com.mindsafe.ai.state.CbtStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * CBT Agent（认知行为治疗）— 对齐 design/13 §2.3 + design/18 SKL-001
 * <p>
 * 基于 CBT 状态机驱动的结构化干预。
 * 状态流转由 CbtStateMachine 引擎驱动（Phase 1.4）。
 * <p>
 * 降级策略：LLM 失败时返回通用支持性回复提示。
 */
@Component
public class CBTAgent implements Agent<CBTAgent.Input, CBTAgent.Result> {

    private static final Logger log = LoggerFactory.getLogger(CBTAgent.class);

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final CbtStateMachine cbtStateMachine;

    public CBTAgent(ChatClient.Builder chatClientBuilder,
                    PromptTemplateService promptTemplateService,
                    CbtStateMachine cbtStateMachine) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplateService = promptTemplateService;
        this.cbtStateMachine = cbtStateMachine;
    }

    @Override
    public String agentName() {
        return "CBTAgent";
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(60);
    }

    @Override
    public Result execute(Input input, ConversationContext context) {
        try {
            String prompt = promptTemplateService.render(PromptTemplateService.SKL_001, Map.of(
                    "grade_level", String.valueOf(context.gradeLevel()),
                    "cbt_state", input.cbtState(),
                    "scenario_id", input.scenarioId() != null ? input.scenarioId() : "general",
                    "emotion_label", input.emotionLabel() != null ? input.emotionLabel() : context.emotionTag(),
                    "emotion_intensity", String.valueOf(input.emotionIntensity()),
                    "trigger_event_summary", input.triggerEventSummary() != null ? input.triggerEventSummary() : "待了解",
                    "auto_thought", input.autoThought() != null ? input.autoThought() : "待捕捉",
                    "turn_count", String.valueOf(context.turnCount())
            ));

            // 将 CBT 技能 prompt 作为 system 补充，用户消息作为 user
            String llmResponse = chatClient.prompt()
                    .system(prompt)
                    .user(input.message())
                    .call()
                    .content();

            // 推断下一状态
            String nextState = inferNextState(input.cbtState(), llmResponse);
            return new Result(llmResponse, nextState, "llm");

        } catch (Exception e) {
            log.warn("CBTAgent LLM 调用失败: {}", e.getMessage());
            return fallback(input, context, e);
        }
    }

    @Override
    public Result fallback(Input input, ConversationContext context, Throwable cause) {
        // 降级：返回通用支持性回复
        String fallbackReply = "我听到你说的了。你的感受很重要，我在这里陪着你。能再多告诉我一点吗？";
        return new Result(fallbackReply, input.cbtState(), "fallback");
    }

    /**
     * 根据当前状态和回复内容，通过 CbtStateMachine 推断下一 CBT 状态
     */
    private String inferNextState(String currentState, String response) {
        CbtState current = CbtState.fromString(currentState);

        // 基于回复内容推断 trigger（简化版：CBT 流程中每轮推进一个状态）
        // 完整实现需要 LLM 结构化输出判断各条件是否满足
        CbtStateMachine.TransitionResult result = cbtStateMachine.evaluateFromOrchestration(
                current,
                true,  // emotionObtained（进入 CBT 时情绪已识别）
                current.ordinal() >= CbtState.S4_EVENT_FACT.ordinal(),
                current.ordinal() >= CbtState.S5_AUTO_THOUGHT.ordinal(),
                current.ordinal() >= CbtState.S6_REFRAME.ordinal(),
                current.ordinal() >= CbtState.S7_MICRO_ACTION.ordinal(),
                null   // riskLevel 由 Orchestrator 层处理
        );

        return result.toState().name();
    }

    // ===== 输入/输出类型 =====

    public record Input(
            String message,
            String cbtState,
            String scenarioId,
            String emotionLabel,
            int emotionIntensity,
            String triggerEventSummary,
            String autoThought
    ) {}

    public record Result(
            String reply,
            String nextState,
            String source
    ) {}
}
