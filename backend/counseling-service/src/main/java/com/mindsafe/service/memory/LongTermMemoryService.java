package com.mindsafe.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mindsafe.ai.risk.EmotionVocabulary;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.LongTermMemory;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.LongTermMemoryMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.notification.RiskNotifyOutboxService;
import com.mindsafe.service.profile.MemoryProfileBackfillService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 长期记忆服务（AI-008）
 * <p>
 * 职责：
 * 1. 会话结束后异步存储关键事件（O 专题 S1：LLM 提炼已上移 MessageSummaryService，
 *    本服务直接接收已解析的 JsonNode key_events）
 * 2. 新会话开始时召回 top-N 记忆供 Prompt 回注
 * 3. 记忆去重（同一会话不重复提取）
 * 4. MEM-101：关键事件回注画像 growthTrack/socialGraph（provenance=memory，design/50 §5.1）
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    /** 每次 Prompt 回注的最大记忆条数 */
    private static final int RECALL_LIMIT = 5;

    /** 记忆最大保留条数（超出时淘汰低重要性旧记忆） */
    private static final int MAX_MEMORIES_PER_STUDENT = 50;

    /** 高敏感情绪标签集合（用于 evaluateForget 敏感度判定） */
    private static final Set<String> SENSITIVE_EMOTIONS = Set.of(
            "self_harm", "suicidal", "abuse", "violence", "crisis", "自伤", "虐待");

    private final LongTermMemoryMapper memoryMapper;
    private final MemoryProfileBackfillService backfillService;
    private final MemoryRiskCorrelator memoryRiskCorrelator;
    private final MemoryRelevanceScorer memoryRelevanceScorer;
    private final ThemeEvolutionEngine themeEvolutionEngine;
    private final RiskEventMapper riskEventMapper;
    private final RiskNotifyOutboxService riskNotifyOutboxService;
    // ARCH-010 P2-5：记忆写入失败 metrics（失败率告警依据）
    private final Counter memoryFailureCounter;

    public LongTermMemoryService(LongTermMemoryMapper memoryMapper,
                                 MemoryProfileBackfillService backfillService,
                                 MemoryRiskCorrelator memoryRiskCorrelator,
                                 MemoryRelevanceScorer memoryRelevanceScorer,
                                 ThemeEvolutionEngine themeEvolutionEngine,
                                 RiskEventMapper riskEventMapper,
                                 RiskNotifyOutboxService riskNotifyOutboxService,
                                 MeterRegistry meterRegistry) {
        this.memoryMapper = memoryMapper;
        this.backfillService = backfillService;
        this.memoryRiskCorrelator = memoryRiskCorrelator;
        this.memoryRelevanceScorer = memoryRelevanceScorer;
        this.themeEvolutionEngine = themeEvolutionEngine;
        this.riskEventMapper = riskEventMapper;
        this.riskNotifyOutboxService = riskNotifyOutboxService;
        this.memoryFailureCounter = Counter.builder("mindsafe.pipeline.failure")
                .tag("stage", "memory")
                .register(meterRegistry);
    }

    /**
     * 异步存储关键事件（S1：LLM 提炼已上移，直接接收已解析事件节点）
     *
     * @param events 已解析的 key_events 数组节点（或含 key_events 字段的根节点）
     */
    @Async
    public void extractAndStoreKeyEvents(UUID tenantId, UUID studentUserId, UUID sessionId,
                                         JsonNode events) {
        try {
            // 幂等：同一会话不重复提取
            Long existing = memoryMapper.selectCount(
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getSessionId, sessionId)
            );
            if (existing != null && existing > 0) {
                log.debug("会话已提取过关键事件，跳过: sessionId={}", sessionId);
                return;
            }

            // 兼容数组节点或含 key_events 字段的根节点
            JsonNode eventArray = events != null && events.isArray() ? events : events.get("key_events");
            if (eventArray == null || !eventArray.isArray() || eventArray.isEmpty()) return;

            int stored = 0;
            List<MemoryProfileBackfillService.MemoryEvent> backfillEvents = new ArrayList<>();
            for (JsonNode event : eventArray) {
                String content = getTextSafe(event, "content");
                if (content == null || content.isBlank()) continue;

                String emotion = getTextSafe(event, "emotion_context");
                float importance = 0.5f;
                if (event.has("importance") && event.get("importance").isNumber()) {
                    importance = (float) event.get("importance").asDouble(0.5);
                    importance = Math.max(0f, Math.min(1f, importance));
                }

                LongTermMemory memory = LongTermMemory.keyEvent(
                        tenantId, studentUserId, sessionId, content, emotion, importance);
                memoryMapper.insert(memory);
                stored++;

                // MEM-101：收集可回注事件（milestone/person 分类由 LLM 提取时给出）
                backfillEvents.add(new MemoryProfileBackfillService.MemoryEvent(
                        getTextSafe(event, "event_type"), content, getTextSafe(event, "person_role"), emotion));
            }

            if (stored > 0) {
                log.info("关键事件提取完成: sessionId={}, stored={}", sessionId, stored);
                evictOldMemories(tenantId, studentUserId);
                // MEM-101：记忆→画像回注（已在异步链路内，失败静默降级）
                backfillService.backfill(tenantId, studentUserId, backfillEvents);
                // MEM-103：纵向风险关联（负面主题趋势信号，非实时报警）
                correlateMemoryRisk(tenantId, studentUserId);
                // MEM-102：主题演化识别（key_event → recurring_theme）
                evolveThemes(tenantId, studentUserId);
            }
        } catch (Exception e) {
            memoryFailureCounter.increment();
            log.warn("关键事件提取失败（不影响业务）: sessionId={}, stage=memory", sessionId, e);
        }
    }

    /**
     * 召回长期记忆，生成 Prompt 片段（对话开始时注入）。
     * <p>
     * MEM-102 接线：使用 {@link MemoryRelevanceScorer} 四因子加权召回，
     * 替代纯 importance+time 排序。当前 vectorSimilarity 以 importance 近似，
     * 向量检索接入后替换为真实相似度。
     *
     * @return 记忆 Prompt 片段，无记忆时返回 null
     */
    public String buildMemoryPrompt(UUID tenantId, UUID studentUserId) {
        try {
            // 取更多候选（MEM-102 多因子排序后取 top）
            int candidateLimit = RECALL_LIMIT * 3;
            // AUD-043：分页插件安全化（不查 COUNT，语义与原 selectList 一致）
            List<LongTermMemory> candidates = memoryMapper.selectPage(
                    new Page<>(1, candidateLimit, false),
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getTenantId, tenantId)
                            .eq(LongTermMemory::getStudentUserId, studentUserId)
                            .orderByDesc(LongTermMemory::getImportance)
                            .orderByDesc(LongTermMemory::getCreatedAt)
            ).getRecords();

            if (candidates == null || candidates.isEmpty()) return null;

            // MEM-102 四因子评分排序
            Instant now = Instant.now();
            List<LongTermMemory> scored = candidates.stream()
                    .filter(m -> {
                        double score = memoryRelevanceScorer.score(
                                m.getImportance() != null ? m.getImportance() : 0.5, // vectorSimilarity 近似
                                m.getImportance() != null ? m.getImportance() : 0.5,
                                m.getCreatedAt(), m.getMemoryType(), now);
                        return memoryRelevanceScorer.isWorthRecalling(score);
                    })
                    .sorted(Comparator.comparingDouble((LongTermMemory m) ->
                            memoryRelevanceScorer.score(
                                    m.getImportance() != null ? m.getImportance() : 0.5,
                                    m.getImportance() != null ? m.getImportance() : 0.5,
                                    m.getCreatedAt(), m.getMemoryType(), now))
                            .reversed())
                    .limit(RECALL_LIMIT)
                    .toList();

            if (scored.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            sb.append("## 历史记忆（跨会话关键事件，仅供个性化参考）\n");
            sb.append("以下是该学生过去对话中的重要事件，可在适当时机自然引用（如'上次你提到...'），但不要生硬复述：\n");

            for (LongTermMemory m : scored) {
                sb.append("- ");
                if (m.getEmotionContext() != null && !m.getEmotionContext().isBlank()) {
                    sb.append("[").append(m.getEmotionContext()).append("] ");
                }
                sb.append(m.getContent()).append("\n");
            }

            // 更新召回计数（fire-and-forget，不影响主流程）
            updateRecallCount(scored);

            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("长期记忆召回失败（不影响对话）: userId={}, error={}", studentUserId, e.getMessage());
            return null;
        }
    }

    /**
     * 多维遗忘策略淘汰记忆（MEM-103 接线）。
     * <p>
     * 使用 {@link MemoryRiskCorrelator#evaluateForget} 多维策略（实际接线 3 维）：
     * 敏感度分级 > 时效衰减 > 数量淘汰。
     * 学生意愿（被遗忘权，PIPL）维度恒 false 未接线——无 forget 请求入口，P2 升级
     * （ARCH-004 台账核对，见 doing/64 §8 与 TASK-TRACKER MEM-103 标注）。
     * 超出 MAX_MEMORIES_PER_STUDENT 时仍做数量兜底。
     */
    private void evictOldMemories(UUID tenantId, UUID studentUserId) {
        List<LongTermMemory> allMemories = memoryMapper.selectList(
                new LambdaQueryWrapper<LongTermMemory>()
                        .eq(LongTermMemory::getTenantId, tenantId)
                        .eq(LongTermMemory::getStudentUserId, studentUserId)
                        .orderByAsc(LongTermMemory::getImportance)
                        .orderByAsc(LongTermMemory::getCreatedAt)
        );
        if (allMemories == null || allMemories.isEmpty()) return;

        Instant now = Instant.now();
        int evicted = 0;
        List<UUID> toDelete = new ArrayList<>();

        // 1. MEM-103 多维遗忘策略：逐条评估决策，命中者收集 id（C1: 收集后批量删除，消除 N+1）
        for (LongTermMemory m : allMemories) {
            MemoryRiskCorrelator.MemoryEntry entry = toMemoryEntry(m);
            MemoryRiskCorrelator.ForgetDecision decision = memoryRiskCorrelator.evaluateForget(entry, now);
            if (decision.shouldForget()) {
                toDelete.add(m.getMemoryId());
                evicted++;
                log.debug("记忆遗忘[{}]: memoryId={}, reason={}", decision.action(), m.getMemoryId(), decision.reason());
            }
        }

        // 2. 数量兜底：仍超上限时按重要性+时间淘汰
        long remaining = allMemories.size() - evicted;
        if (remaining > MAX_MEMORIES_PER_STUDENT) {
            long excess = remaining - MAX_MEMORIES_PER_STUDENT;
            // AUD-043：分页插件安全化（不查 COUNT，语义与原 selectList 一致）
            List<LongTermMemory> survivors = memoryMapper.selectPage(
                    new Page<>(1, excess, false),
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getTenantId, tenantId)
                            .eq(LongTermMemory::getStudentUserId, studentUserId)
                            .orderByAsc(LongTermMemory::getImportance)
                            .orderByAsc(LongTermMemory::getCreatedAt)
            ).getRecords();
            for (LongTermMemory m : survivors) {
                toDelete.add(m.getMemoryId());
            }
            evicted += survivors.size();
        }

        // C1: 一次批量删除替代 N 次 deleteById
        if (!toDelete.isEmpty()) {
            memoryMapper.deleteBatchIds(toDelete);
        }

        if (evicted > 0) {
            log.debug("记忆淘汰完成: userId={}, evicted={}", studentUserId, evicted);
        }
    }

    /**
     * MEM-103 纵向风险关联：分析近期负面主题趋势，生成关注信号。
     * <p>
     * 非实时报警（危机仍走 04 管线），仅做纵向趋势信号。
     * 信号达到 ELEVATED 级别时记录 WARN 日志（后续可接教师关注信号）。
     */
    private void correlateMemoryRisk(UUID tenantId, UUID studentUserId) {
        try {
            // 查询近 30 天记忆
            Instant windowStart = Instant.now().minus(MemoryRiskCorrelator.CORRELATION_WINDOW_DAYS, ChronoUnit.DAYS);
            List<LongTermMemory> recentMemories = memoryMapper.selectList(
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getTenantId, tenantId)
                            .eq(LongTermMemory::getStudentUserId, studentUserId)
                            .ge(LongTermMemory::getCreatedAt, windowStart)
            );
            if (recentMemories == null || recentMemories.size() < MemoryRiskCorrelator.NEGATIVE_THEME_THRESHOLD) return;

            // 转换为 ThemeOccurrence（负面判定：emotionContext 含负面标签）
            List<MemoryRiskCorrelator.ThemeOccurrence> occurrences = recentMemories.stream()
                    .filter(m -> isNegativeEmotion(m.getEmotionContext()))
                    .map(m -> new MemoryRiskCorrelator.ThemeOccurrence(
                            m.getEmotionContext() != null ? m.getEmotionContext() : "unknown",
                            true,
                            m.getCreatedAt()))
                    .toList();

            MemoryRiskCorrelator.RiskSignal signal = memoryRiskCorrelator.correlateRisk(
                    studentUserId.toString(), occurrences, Instant.now());

            if (signal != null) {
                if ("ELEVATED".equals(signal.signalLevel())) {
                    log.warn("📊 MEM-103 纵向风险信号[ELEVATED]: student={}, theme={}, count={}次/{}天, 建议量表复测",
                            studentUserId, signal.theme(), signal.occurrenceCount(), signal.windowDays());
                    // RISK-204 / BL-08：持久化到 risk_events 教师关注通道
                    persistMemoryRiskSignal(tenantId, studentUserId, signal);
                } else {
                    log.info("📊 MEM-103 纵向风险信号[WATCH]: student={}, theme={}, count={}次/{}天",
                            studentUserId, signal.theme(), signal.occurrenceCount(), signal.windowDays());
                }
            }
        } catch (Exception e) {
            log.debug("MEM-103 纵向风险关联失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * MEM-102 主题演化识别：从 key_event 中识别反复出现的主题，生成 recurring_theme 记忆。
     * <p>
     * 铁律：主题表述中性、泛化，不定性孩子；主题识别是辅助信号，不单独触发风险预警。
     */
    private void evolveThemes(UUID tenantId, UUID studentUserId) {
        try {
            // 查询所有 key_event（主题演化需要全量事件）
            List<LongTermMemory> keyEvents = memoryMapper.selectList(
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getTenantId, tenantId)
                            .eq(LongTermMemory::getStudentUserId, studentUserId)
                            .eq(LongTermMemory::getMemoryType, "key_event")
                            .orderByAsc(LongTermMemory::getCreatedAt)
            );
            if (keyEvents == null || keyEvents.size() < 3) return;

            // 转换为 EventSnippet
            List<ThemeEvolutionEngine.EventSnippet> snippets = keyEvents.stream()
                    .map(m -> new ThemeEvolutionEngine.EventSnippet(
                            m.getContent(), m.getEmotionContext(), m.getCreatedAt()))
                    .toList();

            // 识别主题
            List<ThemeEvolutionEngine.ThemeCandidate> themes = themeEvolutionEngine.identifyThemes(snippets);
            if (themes.isEmpty()) return;

            // 查询已有 recurring_theme（避免重复创建）
            List<LongTermMemory> existingThemes = memoryMapper.selectList(
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getTenantId, tenantId)
                            .eq(LongTermMemory::getStudentUserId, studentUserId)
                            .eq(LongTermMemory::getMemoryType, "recurring_theme")
            );
            Set<String> existingContents = existingThemes != null
                    ? existingThemes.stream().map(LongTermMemory::getContent).collect(java.util.stream.Collectors.toSet())
                    : Set.of();

            // 持久化新主题
            for (ThemeEvolutionEngine.ThemeCandidate theme : themes) {
                String themeContent = themeEvolutionEngine.generateThemeContent(theme);
                // 简单去重：内容前缀匹配（同一 themeKey 不重复创建）
                boolean exists = existingContents.stream().anyMatch(c -> c.contains(theme.themeLabel()));
                if (exists) continue;

                LongTermMemory themeMemory = new LongTermMemory();
                themeMemory.setMemoryId(UUID.randomUUID());
                themeMemory.setTenantId(tenantId);
                themeMemory.setStudentUserId(studentUserId);
                themeMemory.setMemoryType("recurring_theme");
                themeMemory.setContent(themeContent);
                themeMemory.setEmotionContext(theme.dominantEmotion());
                themeMemory.setImportance(0.7f); // recurring_theme 默认高重要性
                themeMemory.setRecallCount(0);
                themeMemory.setCreatedAt(Instant.now());
                themeMemory.setUpdatedAt(Instant.now());
                memoryMapper.insert(themeMemory);

                log.info("📝 MEM-102 主题识别: student={}, theme={}, count={}, trend={}",
                        studentUserId, theme.themeLabel(), theme.occurrenceCount(), theme.trend());
            }
        } catch (Exception e) {
            log.debug("MEM-102 主题演化失败（不影响业务）: {}", e.getMessage());
        }
    }

    /** 将 LongTermMemory 实体转换为 MemoryRiskCorrelator.MemoryEntry */
    private MemoryRiskCorrelator.MemoryEntry toMemoryEntry(LongTermMemory m) {
        boolean sensitive = isSensitiveEmotion(m.getEmotionContext());
        boolean isRecurring = "recurring_theme".equals(m.getMemoryType());
        Instant lastRecalled = m.getLastRecalledAt() != null ? m.getLastRecalledAt() : m.getCreatedAt();
        return new MemoryRiskCorrelator.MemoryEntry(
                m.getMemoryId().toString(),
                m.getImportance() != null ? m.getImportance().doubleValue() : 0.5,
                sensitive,
                false, // studentRequestedForget：当前无此字段，PIPL 被遗忘权为 P2 升级
                lastRecalled,
                m.getCreatedAt(),
                isRecurring);
    }

    private boolean isNegativeEmotion(String emotion) {
        // ARCH-003：内嵌 contains 子串语义 → EmotionVocabulary 统一判定（原语义已全收编，行为零变更）
        return EmotionVocabulary.isNegative(emotion);
    }

    private boolean isSensitiveEmotion(String emotion) {
        if (emotion == null) return false;
        return SENSITIVE_EMOTIONS.contains(emotion.toLowerCase());
    }

    private void updateRecallCount(List<LongTermMemory> memories) {
        try {
            if (memories == null || memories.isEmpty()) return;
            // C1: 批量 SQL 更新（recall_count = recall_count + 1），消除 N 次 updateById。
            // 用 UpdateWrapper（字符串列名）而非 LambdaUpdateWrapper：set() 会立即解析 lambda 列名，
            // 依赖 MyBatis 运行时 TableInfo，单元测试环境（无 MyBatis）会抛异常。
            List<UUID> ids = memories.stream().map(LongTermMemory::getMemoryId).toList();
            memoryMapper.update(null, new UpdateWrapper<LongTermMemory>()
                    .in("memory_id", ids)
                    .setSql("recall_count = recall_count + 1")
                    .set("last_recalled_at", Instant.now()));
        } catch (Exception e) {
            log.debug("更新召回计数失败（忽略）: {}", e.getMessage());
        }
    }

    private String getTextSafe(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isTextual()) {
            return node.get(field).asText();
        }
        return null;
    }

    /**
     * RISK-204 / BL-08：将 MEM-103 纵向风险 ELEVATED 信号持久化到 risk_events。
     * <p>
     * source_type=attention 标识非实时关注信号；risk_level=YELLOW(1) 不触发紧急通知；
     * detected_by=memory_correlator 标识信号来源。失败安全：异常仅记日志。
     */
    private void persistMemoryRiskSignal(UUID tenantId, UUID studentUserId, MemoryRiskCorrelator.RiskSignal signal) {
        try {
            RiskEvent event = new RiskEvent();
            event.setRiskEventId(UUID.randomUUID());
            event.setTenantId(tenantId);
            event.setStudentUserId(studentUserId);
            event.setSourceType("attention");
            event.setRiskType("memory_risk:" + signal.theme());
            event.setRiskLevel(RiskLevel.YELLOW.severity()); // 非实时关注信号
            event.setDetectedBy("memory_correlator");
            event.setDetectedAt(Instant.now());
            event.setStatus(RiskEvent.STATUS_OPEN);
            event.setCreatedAt(Instant.now());
            event.setUpdatedAt(Instant.now());
            riskEventMapper.insert(event);
            // P0-4：无通知义务的事件标记完成态，防止补偿任务误重试留痕事件
            riskNotifyOutboxService.markSent(event);
            log.info("RISK-204 记忆风险信号已持久化: riskEventId={}, theme={}", event.getRiskEventId(), signal.theme());
        } catch (Exception e) {
            log.warn("RISK-204 记忆风险持久化降级（不影响业务）: {}", e.getMessage());
        }
    }
}
