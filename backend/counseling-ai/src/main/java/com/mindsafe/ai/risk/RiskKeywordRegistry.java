package com.mindsafe.ai.risk;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 风险知识单一规则源（ARCH-003，doing/63 §3.1/§4.1）。
 * <p>
 * 只读深模块：四级分级词典 + 意图/方法/准备词 + 风险类别表 + 评分因子常量。
 * 消费点只引用不定义（风险判定词表与评分魔法数收敛于此），杜绝同一信号多处拷贝导致的行为漂移。
 * <p>
 * 收编来源（2026-08-06 调研核对）：
 * <ul>
 *   <li>RiskDetectorServiceImpl RED_HARD/ORANGE/YELLOW/NEGATION_WORDS/CONTEXT_PATTERN/RISK_KEYWORDS</li>
 *   <li>ConversationRiskProcessor EXPLICIT_INTENT/VAGUE_INTENT/SELF_HARM_METHOD/PREPARATORY + 权重魔法数</li>
 * </ul>
 * 例外（引用分析结论，语义不同不收编）：TemplateMatrixRegistry 红队用例（测试资产）、
 * SafetyKeywordLibrary 输出过滤词库（AI 不应说的话，非输入判定）。
 * <p>
 * 设计铁律：静态只读、无状态无副作用；增删词典唯一入口即本类。
 */
@Component
public class RiskKeywordRegistry {

    public RiskKeywordRegistry() {
    }

    // ===== 评分常量（design/04 §九铁律 + §十权重表） =====

    /** 红色硬规则命中评分 */
    public final int SCORE_HARD = 85;
    /** 橙色命中评分 */
    public final int SCORE_ORANGE = 60;
    /** 橙色命中但含否定/引用语境降为黄色时的评分 */
    public final int SCORE_YELLOW = 35;
    /** 黄色命中评分 */
    public final int SCORE_ORANGE_MIN = 30;
    /** 语义层升级到黄色档位的评分（ConversationRiskProcessor 语义融合路径） */
    public final int SCORE_SEMANTIC_YELLOW = 40;

    // ===== 评分因子权重（RISK-203 结构化评分，design/04 §十权重表） =====

    /** 明确自伤意图权重（I=+15） */
    public final int INTENT_EXPLICIT_WEIGHT = 15;
    /** 含混死亡愿望权重（I=+8） */
    public final int INTENT_VAGUE_WEIGHT = 8;
    /** 方法类关键词每个权重（P 每类 +5） */
    public final int PLAN_WEIGHT_PER_KEYWORD = 5;
    /** 计划权重上限 */
    public final int PLAN_WEIGHT_CAP = 20;
    /** ScoreInput recencyWeight（当前会话=今天） */
    public final int WEIGHT_RECENCY = 10;
    /** ScoreInput actionWeight */
    public final int WEIGHT_ACTION = 0;
    /** ScoreInput repetitionWeight */
    public final int WEIGHT_REPETITION = 0;
    /** ScoreInput protectiveWeight */
    public final int WEIGHT_PROTECTIVE = 0;
    /** ScoreInput confidenceAdjustment（硬规则默认置信） */
    public final double WEIGHT_CONFIDENCE = 0.8;

    // ===== 分级词典（来源：RiskDetectorServiceImpl L38-63，design/04 §五） =====

    /** 红色硬规则关键词（命中即强制升级为红色） */
    public final Set<String> RED_HARD = Set.of(
            "想死", "跳楼", "割腕", "上吊", "遗书", "活着没意思",
            "不想活", "去死", "自杀", "杀了自己", "结束生命", "伤害自己",
            "带刀", "放火", "炸",
            "摸隐私部位", "隐私部位", "脱衣服", "发裸照"
    );

    /** 橙色关键词（命中升级为橙色，需人工核验） */
    public final Set<String> ORANGE = Set.of(
            "不想活了", "死了算了", "活着很累", "没希望",
            "杀了他", "打死", "报复", "砸学校",
            "被打", "被围", "被孤立", "勒索",
            "被欺负", "欺负我", "霸凌", "威胁我", "抢我东西", "堵我",
            // F-2（2026-08-09）：高频"被X欺负"变体——原"被欺负"不命中"被同学欺负"（中间插词）致漏报
            "被同学欺负", "被朋友欺负", "被人欺负", "被他们欺负",
            "赶出家门", "锁屋里", "不给饭",
            "打我", "揍我", "扇我", "家暴", "用皮带抽",
            "离家出走", "不回家", "见网友",
            "喘不过气", "要崩溃", "不能上学"
    );

    /** 黄色关键词（命中为黄色，需关注） */
    public final Set<String> YELLOW = Set.of(
            "很难过", "每天哭", "睡不着", "吃不下",
            "被骂", "被嘲笑", "没朋友", "不想上学",
            "头痛", "肚子痛", "恶心", "胸闷",
            "停不下来", "通宵游戏", "偷钱"
    );

    /** 否定词列表（仅保留明确的多字否定词，避免单字误匹配；否定降噪仅适用橙/黄档） */
    public final Set<String> NEGATION_WORDS = Set.of(
            "不想", "不会", "没有", "不是", "不能", "以前", "曾经", "别想", "别要"
    );

    /** 引用/假设语境（降低误报） */
    public final Pattern CONTEXT_PATTERN = Pattern.compile(
            "(故事里|新闻|游戏|电影|电视|书上|假设|如果|假如|老师说的|健康课)"
    );

    // ===== 意图/方法/准备词（来源：ConversationRiskProcessor L225-240） =====

    /** 明确自伤意图（I=+15）："我不想活了" 等直接表达 */
    public final Set<String> EXPLICIT_INTENT_KEYWORDS = Set.of(
            "想死", "自杀", "去死", "杀了自己", "结束生命", "伤害自己", "不想活", "不想活了", "死了算了"
    );

    /** 含混死亡愿望（I=+8）："活着没意思" 等间接表达 */
    public final Set<String> VAGUE_INTENT_KEYWORDS = Set.of(
            "活着没意思", "活着很累", "没希望", "什么都没意思", "我是累赘", "把东西送人", "告别"
    );

    /** 自伤方法/工具关键词（P 每类 +5，上限 20） */
    public final Set<String> SELF_HARM_METHOD_KEYWORDS = Set.of(
            "跳楼", "上吊", "割腕", "吃药", "带刀"
    );

    /** 准备行为关键词（C-SSRS 行为轴 PREPARATORY） */
    public final Set<String> PREPARATORY_KEYWORDS = Set.of("遗书");

    // ===== 风险类别表（来源：RiskDetectorServiceImpl L76-117，10 类） =====

    /** 风险类别 → 关键词列表（保持插入顺序：findCategory 取首个命中类别） */
    public final Map<String, List<String>> RISK_KEYWORDS =
            Collections.unmodifiableMap(buildCategoryMap());

    private Map<String, List<String>> buildCategoryMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("自伤/自杀", List.of(
                "想死", "跳楼", "割腕", "吃药", "上吊", "遗书", "活着没意思",
                "不想活", "去死", "自杀", "杀了自己", "结束生命", "不想活了",
                "死了算了", "活着很累", "没希望", "把东西送人", "告别"
        ));
        map.put("他伤/暴力", List.of(
                "杀了他", "打死", "报复", "带刀", "砸学校", "放火", "炸",
                "列名单", "跟踪", "埋伏"
        ));
        map.put("霸凌/网络欺凌", List.of(
                "被打", "被围", "被孤立", "被拍视频", "群里骂", "勒索",
                "被欺负", "欺负我", "霸凌", "威胁我", "抢我东西", "堵我",
                // F-2：与 ORANGE 词典对齐的高频变体（"被同学欺负"等中间插词）
                "被同学欺负", "被朋友欺负", "被人欺负", "被他们欺负",
                "让我下跪", "不敢去学校", "发照片", "发视频", "谣言"
        ));
        map.put("家庭虐待/忽视", List.of(
                "赶出家门", "锁屋里", "不给饭", "皮带", "酒后打我",
                "打我", "揍我", "扇我", "家暴", "用皮带抽",
                "害怕回家", "受伤不能说"
        ));
        map.put("性侵/性骚扰", List.of(
                "摸隐私部位", "脱衣服", "发裸照", "让我保密", "恶心的事情",
                "不敢拒绝"
        ));
        map.put("离家/失联", List.of(
                "离家出走", "不回家", "去车站", "睡桥洞", "见网友",
                "在路上", "带行李"
        ));
        map.put("严重焦虑/恐慌", List.of(
                "喘不过气", "心跳快", "要崩溃", "手抖", "不能上学",
                "惊恐", "晕倒"
        ));
        map.put("严重抑郁/绝望", List.of(
                "什么都没意思", "我是累赘", "每天哭", "活着很累", "没希望",
                "把东西送人"
        ));
        map.put("躯体化/进食睡眠", List.of(
                "吃不下", "暴食", "噩梦", "睡不着", "头痛", "肚子痛",
                "恶心", "胸闷"
        ));
        map.put("成瘾/网络沉迷", List.of(
                "停不下来", "通宵游戏", "偷钱充值", "烟", "酒", "药片"
        ));
        return map;
    }

    // ===== 只读查找接口 =====

    /** 命中档位 */
    public enum Level {
        RED_HARD, ORANGE, YELLOW, NONE
    }

    /**
     * 按文本命中最高风险档位（判定顺序与 design/04 铁律一致：RED → ORANGE → YELLOW）。
     *
     * @param text 原始文本（不做大小写/空白归一，与 RiskDetectorServiceImpl 原行为一致）
     * @return 命中档位；无命中返回 NONE
     */
    public Level matchLevel(String text) {
        if (text == null || text.isBlank()) {
            return Level.NONE;
        }
        if (containsAny(text, RED_HARD)) {
            return Level.RED_HARD;
        }
        if (containsAny(text, ORANGE)) {
            return Level.ORANGE;
        }
        if (containsAny(text, YELLOW)) {
            return Level.YELLOW;
        }
        return Level.NONE;
    }

    /**
     * 命中意图/方法/准备词（RISK-203 评分因子抽取用）。
     *
     * @param text 文本
     * @return 命中的关键词列表（可能为空列表，永不为 null）
     */
    public List<String> matchMethod(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        java.util.ArrayList<String> hits = new java.util.ArrayList<>();
        addHits(text, hits, EXPLICIT_INTENT_KEYWORDS);
        addHits(text, hits, VAGUE_INTENT_KEYWORDS);
        addHits(text, hits, SELF_HARM_METHOD_KEYWORDS);
        addHits(text, hits, PREPARATORY_KEYWORDS);
        return java.util.List.copyOf(hits);
    }

    /**
     * 依据档位返回风险评分（替代散落魔法数实现）。
     * <p>
     * 注意：仅做"档位 → 分数"映射，不含否定/引用语境降级逻辑（该逻辑保留在
     * RiskDetectorServiceImpl 判定路径中，降级分数用 {@link #SCORE_YELLOW} 常量引用）。
     *
     * @param text 文本
     * @return 档位对应评分；无命中返回 0
     */
    public int scoreFor(String text) {
        return switch (matchLevel(text)) {
            case RED_HARD -> SCORE_HARD;
            case ORANGE -> SCORE_ORANGE;
            case YELLOW -> SCORE_YELLOW;
            case NONE -> 0;
        };
    }

    /**
     * 根据命中关键词查找所属风险类别（首个命中的类别，与 RiskDetectorServiceImpl 原逻辑一致）。
     *
     * @param matchedKeywords 命中关键词
     * @return 类别名；无命中返回 "未分类"
     */
    public String findCategory(List<String> matchedKeywords) {
        if (matchedKeywords == null || matchedKeywords.isEmpty()) {
            return "未分类";
        }
        for (Map.Entry<String, List<String>> entry : RISK_KEYWORDS.entrySet()) {
            for (String keyword : matchedKeywords) {
                if (entry.getValue().contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return "未分类";
    }

    // ===== 高敏/不降级类别（DC-001，doing/72 §16：SAFE-202 中文权威类别单一源） =====

    /**
     * SAFE-202 高敏类别（中文权威类别子集）：对应原英文集
     * physical_abuse/sexual_abuse/domestic_violence/neglect/self_harm/suicidal_ideation；
     * bereavement 中文无对应类别不新增（YAGNI）。
     */
    public final Set<String> HIGH_SENSITIVITY_CATEGORIES = Set.of(
            "自伤/自杀", "他伤/暴力", "家庭虐待/忽视", "性侵/性骚扰", "严重抑郁/绝望"
    );

    /** 不降级类别（性侵/虐待类不可因否定/语境降级）——RISK-104 字符串 contains 语义收敛 */
    public final Set<String> NON_DEGRADABLE_CATEGORIES = Set.of(
            "性侵/性骚扰", "家庭虐待/忽视"
    );

    /**
     * 判断类别是否属于高敏场景（SAFE-202 门控，会话标记高敏模式）。
     *
     * @param category 中文风险类别
     * @return true=高敏类别
     */
    public boolean isHighSensitivityCategory(String category) {
        return category != null && HIGH_SENSITIVITY_CATEGORIES.contains(category);
    }

    /**
     * 判断类别是否不可降级（否定/引用语境不可降级，RISK-104）。
     *
     * @param category 中文风险类别
     * @return true=不可降级
     */
    public boolean isNonDegradableCategory(String category) {
        return category != null && NON_DEGRADABLE_CATEGORIES.contains(category);
    }

    private boolean containsAny(String text, Set<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void addHits(String text, java.util.List<String> hits, Set<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits.add(keyword);
            }
        }
    }
}
