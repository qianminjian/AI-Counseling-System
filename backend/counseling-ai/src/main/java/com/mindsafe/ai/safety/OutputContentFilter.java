package com.mindsafe.ai.safety;

import com.mindsafe.common.dto.chat.StreamMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Layer1：AI 输出实时内容过滤器（流式硬拦截）。
 * <p>
 * 对 LLM 流式 token 做<b>滑动窗口</b>敏感词匹配（窗口长度 = 词库最长关键词，
 * 防止关键词被 token 切碎而漏检，如「自」+「杀」）。命中 block 级关键词时：
 * <ol>
 *   <li>立即中断输出流（takeUntil 触发 complete，自动取消上游 LLM 生成）；</li>
 *   <li>发射安全话术替换后续内容（自伤/伤人类追加危机热线 400-161-9995，硬编码不交给 LLM）；</li>
 *   <li>通过 {@link OutputSafetyReporter} 写 risk_events 并触发教师通知。</li>
 * </ol>
 * <p>
 * 设计权衡（对齐 design/14 儿童安全对话规范）：
 * <ul>
 *   <li>词库保守：宁可漏（交给 Layer2 语义审查）不可错杀正常安慰语；</li>
 *   <li>仅小窗口 contains 匹配，延迟可忽略，正常流零干扰；</li>
 *   <li>block 时不发射 done 事件，由调用方（ConversationServiceImpl）统一追加，保证单一 done。</li>
 * </ul>
 */
@Component
public class OutputContentFilter {

    private static final Logger log = LoggerFactory.getLogger(OutputContentFilter.class);

    /** 危机热线（硬编码常量：危机干预资源绝不交给 LLM 决定，遵 design/14 铁律） */
    static final String CRISIS_HOTLINE = "400-161-9995";

    /** 自伤/伤人方法类目（命中时追加危机热线） */
    private static final String CATEGORY_SELF_HARM = "self_harm_method";

    /** 上报片段最大长度（审计用，避免记录过长内容） */
    private static final int SNIPPET_MAX_LENGTH = 100;

    private final SafetyKeywordLibrary library;
    private final OutputSafetyReporter reporter;

    public OutputContentFilter(SafetyKeywordLibrary library, OutputSafetyReporter reporter) {
        this.library = library;
        this.reporter = reporter;
    }

    /**
     * 对 LLM 原始 token 流应用实时安全过滤。
     *
     * @param tokens    LLM 流式输出的原始 token
     * @param sessionId 会话 ID（违规上报用）
     * @return 过滤后的 SSE 事件流；命中 block 级关键词时流被中断并以安全话术收尾
     */
    public Flux<StreamMessageEvent> apply(Flux<String> tokens, UUID sessionId) {
        int windowSize = library.maxKeywordLength();
        StringBuilder window = new StringBuilder();
        AtomicBoolean blocked = new AtomicBoolean(false);

        return tokens
                .concatMap(token -> {
                    if (blocked.get()) {
                        return Flux.<StreamMessageEvent>empty();
                    }
                    window.append(token);
                    if (window.length() > windowSize) {
                        window.delete(0, window.length() - windowSize);
                    }
                    SafetyKeywordLibrary.KeywordHit hit = library.matchBlock(window.toString());
                    if (hit != null) {
                        blocked.set(true);
                        String snippet = window.toString();
                        log.warn("🛑 Layer1 输出拦截: sessionId={}, category={}, keyword={}",
                                sessionId, hit.category(), hit.keyword());
                        reportBlock(sessionId, hit, snippet);
                        // 发射安全话术后 complete（takeUntil 会取消上游 LLM 生成）
                        return Flux.just(StreamMessageEvent.token(safeTemplate(hit)));
                    }
                    return Flux.just(StreamMessageEvent.token(token));
                })
                .takeUntil(evt -> blocked.get());
    }

    /** 违规上报（异常不中断流处理） */
    private void reportBlock(UUID sessionId, SafetyKeywordLibrary.KeywordHit hit, String snippet) {
        try {
            String truncated = snippet.length() > SNIPPET_MAX_LENGTH
                    ? snippet.substring(snippet.length() - SNIPPET_MAX_LENGTH)
                    : snippet;
            reporter.reportLayer1Block(sessionId, hit.category(), hit.keyword(), truncated);
        } catch (Exception e) {
            log.error("Layer1 违规上报失败（不影响流处理）: sessionId={}", sessionId, e);
        }
    }

    /**
     * 按命中类目返回安全话术。
     * <p>
     * 自伤/伤人类：温和打断 + 危机热线（硬编码）；
     * 其他类目：温和打断 + 话题转移。
     */
    String safeTemplate(SafetyKeywordLibrary.KeywordHit hit) {
        if (CATEGORY_SELF_HARM.equals(hit.category())) {
            return "\n\n我很担心你的安全。如果你正在经历很难受的时刻，请拨打24小时心理援助热线 "
                    + CRISIS_HOTLINE + "，会有专业的老师帮助你。你不是一个人。💙";
        }
        return "\n\n我们换个话题聊聊好吗？你可以和我说说现在的心情。🌈";
    }
}
