package com.mindsafe.ai.agent;

import java.time.Duration;

/**
 * Agent 统一接口（对齐 design/13 §9.1）
 * <p>
 * 每个 Agent 封装为独立 Spring Bean，由 ConversationOrchestrator 编排调用。
 * 泛型 I/O 确保类型安全的输入输出契约。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface Agent<I, O> {

    /** Agent 名称（用于日志和 model_call_logs） */
    String agentName();

    /** 执行 Agent 逻辑 */
    O execute(I input, ConversationContext context);

    /** 超时时间（超时触发 fallback） */
    Duration timeout();

    /** 降级策略（LLM 失败/超时时调用） */
    O fallback(I input, ConversationContext context, Throwable cause);
}
