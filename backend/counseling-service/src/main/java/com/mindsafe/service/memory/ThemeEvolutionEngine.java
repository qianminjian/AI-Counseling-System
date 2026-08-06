package com.mindsafe.service.memory;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主题演化引擎（MEM-102，design/50 §六）
 * <p>
 * 从"记事件"到"识主题"：对 key_event 做关键词聚类，
 * 达到出现次数阈值 → 识别为 recurring_theme（反复出现的困扰/关注）。
 * <p>
 * 纯规则实现（关键词匹配 + 计数），不依赖 LLM/向量。
 * 向量聚类为 P2 升级方向（KB-103 混合检索基座复用）。
 * <p>
 * 铁律：
 * <ul>
 *   <li>主题表述中性、泛化，不定性孩子</li>
 *   <li>recurring_theme 可遗忘（淘汰策略同 key_event）</li>
 *   <li>主题识别是辅助信号，不单独触发风险预警</li>
 * </ul>
 */
@Component
public class ThemeEvolutionEngine {

    /** 主题触发阈值：同一关键词簇出现 ≥3 次 → 识别为主题 */
    private static final int OCCURRENCE_THRESHOLD = 3;

    /** 主题关键词注册表（可扩展） */
    private static final Map<String, List<String>> THEME_KEYWORDS = Map.ofEntries(
            Map.entry("peer_conflict", List.of("同桌", "同学", "朋友", "吵架", "打架", "孤立", "排挤", "霸凌")),
            Map.entry("academic_pressure", List.of("考试", "成绩", "作业", "分数", "不及格", "补课", "排名")),
            Map.entry("family_tension", List.of("爸妈", "父母", "家里", "离婚", "吵架", "挨打", "骂")),
            Map.entry("self_worth", List.of("笨", "没用", "讨厌自己", "不如", " 差劲", "失败")),
            Map.entry("separation_anxiety", List.of("分离", "不想上学", "害怕", "一个人", "丢下")),
            Map.entry("sleep_issue", List.of("睡不着", "噩梦", "失眠", "害怕黑", "半夜"))
    );
    
    /** 单轮话题关键词注册表（CTX-Agent Phase 5，ARCH-001 C1 收敛单一源） */
    public record TopicPattern(String keyword, String label) {
    }
    
    /** 话题关键词表（表序即优先级，每轮最多命中 1 个；自 ConversationServiceImpl.extractTopicHint 原样迁移） */
    public static final List<TopicPattern> TOPIC_PATTERNS = List.of(
            new TopicPattern("同学", "同学关系"), new TopicPattern("朋友", "友谊"),
            new TopicPattern("妈妈", "和妈妈的关系"), new TopicPattern("爸爸", "和爸爸的关系"),
            new TopicPattern("老师", "和老师的关系"),
            new TopicPattern("考试", "考试压力"), new TopicPattern("成绩", "学习压力"), new TopicPattern("作业", "学习压力"),
            new TopicPattern("欺负", "被欺负"), new TopicPattern("打我", "被欺负"), new TopicPattern("骂我", "被欺负"),
            new TopicPattern("不想活", "自伤倾向"), new TopicPattern("死", "自伤倾向"),
            new TopicPattern("孤独", "孤独感"), new TopicPattern("没人", "孤独感"),
            new TopicPattern("害怕", "恐惧"), new TopicPattern("担心", "焦虑"),
            new TopicPattern("生气", "愤怒"), new TopicPattern("讨厌", "厌恶"),
            new TopicPattern("弟弟", "兄弟姐妹关系"), new TopicPattern("妹妹", "兄弟姐妹关系")
    );

    /** 主题识别结果 */
    public record ThemeCandidate(
            String themeKey,
            String themeLabel,
            int occurrenceCount,
            Set<String> matchedKeywords,
            String dominantEmotion,
            Trend trend
    ) {
    }

    /** 主题趋势 */
    public enum Trend {
        ESCALATING,   // 加剧（近期频率上升）
        STABLE,       // 稳定
        ALLEVIATING   // 缓解（近期频率下降）
    }

    /** 输入事件（从 key_event 提取的简化视图） */
    public record EventSnippet(String content, String emotion, Instant occurredAt) {
    }

    /**
     * 单条消息话题提取（CTX-Agent Phase 5，ARCH-001 C1 收敛单一源）。
     * <p>
     * 行为基线：自 ConversationServiceImpl.extractTopicHint 关键词表原样迁移——
     * 内容 null 或 <4 字符返回 null；按表序扫描，每轮最多返回 1 个主题标签；
     * 风险类别主题不在此处（调用方由 RiskDetectionResult.category 直接处理）。
     *
     * @param content 学生本轮消息原文
     * @return 命中主题标签（如"同学关系"），无命中返回 null
     */
    public String findTopicHint(String content) {
        if (content == null || content.length() < 4) return null;
        for (TopicPattern pattern : TOPIC_PATTERNS) {
            if (content.contains(pattern.keyword())) {
                return pattern.label();
            }
        }
        return null;
    }

    /**
     * 从一组 key_event 中识别反复出现的主题。
     *
     * @param events 关键事件列表（按时间正序）
     * @return 达到阈值的主题候选列表（可能为空）
     */
    public List<ThemeCandidate> identifyThemes(List<EventSnippet> events) {
        if (events == null || events.size() < OCCURRENCE_THRESHOLD) return List.of();

        // 1. 按主题关键词聚类
        Map<String, List<EventSnippet>> clustered = new LinkedHashMap<>();
        Map<String, Set<String>> matchedKw = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : THEME_KEYWORDS.entrySet()) {
            String themeKey = entry.getKey();
            List<String> keywords = entry.getValue();
            List<EventSnippet> matched = new ArrayList<>();
            Set<String> hits = new LinkedHashSet<>();

            for (EventSnippet event : events) {
                for (String kw : keywords) {
                    if (event.content() != null && event.content().contains(kw)) {
                        matched.add(event);
                        hits.add(kw);
                        break; // 一个事件只计一次
                    }
                }
            }

            if (matched.size() >= OCCURRENCE_THRESHOLD) {
                clustered.put(themeKey, matched);
                matchedKw.put(themeKey, hits);
            }
        }

        // 2. 构建 ThemeCandidate
        List<ThemeCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<EventSnippet>> entry : clustered.entrySet()) {
            String themeKey = entry.getKey();
            List<EventSnippet> matched = entry.getValue();

            String dominantEmotion = computeDominantEmotion(matched);
            Trend trend = computeTrend(matched);

            candidates.add(new ThemeCandidate(
                    themeKey,
                    themeLabel(themeKey),
                    matched.size(),
                    matchedKw.get(themeKey),
                    dominantEmotion,
                    trend
            ));
        }

        // 按出现次数降序
        candidates.sort(Comparator.comparingInt(ThemeCandidate::occurrenceCount).reversed());
        return candidates;
    }

    /**
     * 生成 recurring_theme 记忆内容（泛化、中性表述）。
     */
    public String generateThemeContent(ThemeCandidate candidate) {
        return String.format("反复出现的关注：%s（近段时间提及 %d 次，情绪基调 %s，趋势 %s）",
                candidate.themeLabel(),
                candidate.occurrenceCount(),
                candidate.dominantEmotion() != null ? candidate.dominantEmotion() : "混合",
                trendLabel(candidate.trend()));
    }

    // ===== 内部方法 =====

    private String computeDominantEmotion(List<EventSnippet> events) {
        Map<String, Long> emotionCounts = events.stream()
                .filter(e -> e.emotion() != null && !e.emotion().isBlank())
                .collect(Collectors.groupingBy(EventSnippet::emotion, Collectors.counting()));
        return emotionCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private Trend computeTrend(List<EventSnippet> events) {
        if (events.size() < 4) return Trend.STABLE;

        // 按时间排序
        List<EventSnippet> sorted = events.stream()
                .filter(e -> e.occurredAt() != null)
                .sorted(Comparator.comparing(EventSnippet::occurredAt))
                .toList();
        if (sorted.size() < 4) return Trend.STABLE;

        // 密度比较：后半段事件的时间跨度 vs 前半段
        // 后半段跨度更短 = 事件更密集 = 加剧
        int mid = sorted.size() / 2;
        Instant firstStart = sorted.get(0).occurredAt();
        Instant midTime = sorted.get(mid).occurredAt();
        Instant lastTime = sorted.get(sorted.size() - 1).occurredAt();

        long firstSpanDays = Math.max(1, ChronoUnit.DAYS.between(firstStart, midTime));
        long secondSpanDays = Math.max(1, ChronoUnit.DAYS.between(midTime, lastTime));

        // 密度 = 事件数 / 时间跨度
        double firstDensity = (double) mid / firstSpanDays;
        double secondDensity = (double) (sorted.size() - mid) / secondSpanDays;

        if (secondDensity > firstDensity * 1.5) return Trend.ESCALATING;
        if (firstDensity > secondDensity * 1.5) return Trend.ALLEVIATING;
        return Trend.STABLE;
    }

    private static String themeLabel(String themeKey) {
        return switch (themeKey) {
            case "peer_conflict" -> "同伴关系困扰";
            case "academic_pressure" -> "学业压力";
            case "family_tension" -> "家庭关系紧张";
            case "self_worth" -> "自我价值感低落";
            case "separation_anxiety" -> "分离焦虑";
            case "sleep_issue" -> "睡眠困扰";
            default -> themeKey;
        };
    }

    private static String trendLabel(Trend trend) {
        return switch (trend) {
            case ESCALATING -> "加剧";
            case STABLE -> "稳定";
            case ALLEVIATING -> "缓解";
        };
    }
}
