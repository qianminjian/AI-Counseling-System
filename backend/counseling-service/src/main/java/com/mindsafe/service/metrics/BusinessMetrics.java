package com.mindsafe.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * MindSafe 业务指标收集器（Prometheus 可观测）
 * <p>
 * 暴露指标：
 * - mindsafe_sessions_created_total（会话创建计数）
 * - mindsafe_sessions_completed_total（会话完成计数）
 * - mindsafe_risk_events_total（风险事件计数，按 level 标签）
 * - mindsafe_messages_sent_total（消息发送计数，按 sender 标签）
 * - mindsafe_tts_requests_total（TTS 合成请求计数）
 * - mindsafe_voice_analyze_total（语音分析请求计数）
 * - mindsafe_llm_latency（LLM 响应延迟直方图）
 */
@Component
public class BusinessMetrics {

    private final Counter sessionsCreated;
    private final Counter sessionsCompleted;
    private final Counter messagesStudent;
    private final Counter messagesAi;
    private final Counter ttsRequests;
    private final Counter voiceAnalyze;
    private final Timer llmLatency;

    public BusinessMetrics(MeterRegistry registry) {
        this.sessionsCreated = Counter.builder("mindsafe_sessions_created_total")
                .description("辅导会话创建总数")
                .register(registry);
        this.sessionsCompleted = Counter.builder("mindsafe_sessions_completed_total")
                .description("辅导会话完成总数")
                .register(registry);
        this.messagesStudent = Counter.builder("mindsafe_messages_sent_total")
                .tag("sender", "student")
                .description("学生发送消息总数")
                .register(registry);
        this.messagesAi = Counter.builder("mindsafe_messages_sent_total")
                .tag("sender", "ai")
                .description("AI 回复消息总数")
                .register(registry);
        this.ttsRequests = Counter.builder("mindsafe_tts_requests_total")
                .description("TTS 合成请求总数")
                .register(registry);
        this.voiceAnalyze = Counter.builder("mindsafe_voice_analyze_total")
                .description("语音分析请求总数")
                .register(registry);
        this.llmLatency = Timer.builder("mindsafe_llm_latency")
                .description("LLM 响应延迟")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void sessionCreated() { sessionsCreated.increment(); }

    public void sessionCompleted() { sessionsCompleted.increment(); }

    public void messageSent(String sender) {
        if ("student".equals(sender)) messagesStudent.increment();
        else messagesAi.increment();
    }

    public void ttsRequested() { ttsRequests.increment(); }

    public void voiceAnalyzed() { voiceAnalyze.increment(); }

    public Timer llmLatency() { return llmLatency; }

    /** 风险事件计数（按等级动态标签） */
    public void riskEventDetected(int level, MeterRegistry registry) {
        Counter.builder("mindsafe_risk_events_total")
                .tag("level", String.valueOf(level))
                .description("风险事件检测总数")
                .register(registry)
                .increment();
    }
}
