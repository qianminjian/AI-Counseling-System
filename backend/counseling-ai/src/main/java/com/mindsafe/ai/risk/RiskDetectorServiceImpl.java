package com.mindsafe.ai.risk;

import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 风险识别服务实现（M1 关键词硬规则版本）
 * <p>
 * 对齐 design/04 风险识别规则库：
 * - 10 类风险信号
 * - 强信号关键词快速召回
 * - 强制升级条件（红色硬规则）
 * - 基础否定/误报控制
 * <p>
 * M2+ 迭代方向：语义分类、上下文评分、LLM 辅助判断、趋势跟踪。
 */
@Service
public class RiskDetectorServiceImpl implements RiskDetectorService {

    private static final Logger log = LoggerFactory.getLogger(RiskDetectorServiceImpl.class);

    /**
     * 风险规则定义：类别 → 关键词列表
     * 来源：design/04 第五节「风险信号分类与规则」
     */
    private static final Map<String, List<String>> RISK_KEYWORDS = new LinkedHashMap<>();

    /** 红色硬规则关键词（命中即强制升级为红色） */
    private static final List<String> RED_HARD_KEYWORDS = List.of(
            "想死", "跳楼", "割腕", "上吊", "遗书", "活着没意思",
            "不想活", "去死", "自杀", "杀了自己", "结束生命",
            "带刀", "放火", "炸",
            "摸隐私部位", "脱衣服", "发裸照"
    );

    /** 橙色关键词（命中升级为橙色，需人工核验） */
    private static final List<String> ORANGE_KEYWORDS = List.of(
            "不想活了", "死了算了", "活着很累", "没希望",
            "杀了他", "打死", "报复", "砸学校",
            "被打", "被围", "被孤立", "勒索",
            "赶出家门", "锁屋里", "不给饭",
            "离家出走", "不回家", "见网友",
            "喘不过气", "要崩溃", "不能上学"
    );

    /** 黄色关键词（命中为黄色，需关注） */
    private static final List<String> YELLOW_KEYWORDS = List.of(
            "很难过", "每天哭", "睡不着", "吃不下",
            "被骂", "被嘲笑", "没朋友", "不想上学",
            "头痛", "肚子痛", "恶心", "胸闷",
            "停不下来", "通宵游戏", "偷钱"
    );

    /** 否定/误报修饰词（降低误报） */
    private static final Pattern NEGATION_PATTERN = Pattern.compile(
            "(不|没有|不会|不是|别|以前|曾经).{0,4}(想死|跳楼|割腕|自杀|不想活)"
    );

    /** 引用/假设语境（降低误报） */
    private static final Pattern CONTEXT_PATTERN = Pattern.compile(
            "(故事里|新闻|游戏|电影|电视|书上|假设|如果|假如|老师说的|健康课)"
    );

    static {
        RISK_KEYWORDS.put("自伤/自杀", List.of(
                "想死", "跳楼", "割腕", "吃药", "上吊", "遗书", "活着没意思",
                "不想活", "去死", "自杀", "杀了自己", "结束生命", "不想活了",
                "死了算了", "活着很累", "没希望", "把东西送人", "告别"
        ));
        RISK_KEYWORDS.put("他伤/暴力", List.of(
                "杀了他", "打死", "报复", "带刀", "砸学校", "放火", "炸",
                "列名单", "跟踪", "埋伏"
        ));
        RISK_KEYWORDS.put("霸凌/网络欺凌", List.of(
                "被打", "被围", "被孤立", "被拍视频", "群里骂", "勒索",
                "让我下跪", "不敢去学校", "发照片", "发视频", "谣言"
        ));
        RISK_KEYWORDS.put("家庭虐待/忽视", List.of(
                "赶出家门", "锁屋里", "不给饭", "皮带", "酒后打我",
                "害怕回家", "受伤不能说"
        ));
        RISK_KEYWORDS.put("性侵/性骚扰", List.of(
                "摸隐私部位", "脱衣服", "发裸照", "让我保密", "恶心的事情",
                "不敢拒绝"
        ));
        RISK_KEYWORDS.put("离家/失联", List.of(
                "离家出走", "不回家", "去车站", "睡桥洞", "见网友",
                "在路上", "带行李"
        ));
        RISK_KEYWORDS.put("严重焦虑/恐慌", List.of(
                "喘不过气", "心跳快", "要崩溃", "手抖", "不能上学",
                "惊恐", "晕倒"
        ));
        RISK_KEYWORDS.put("严重抑郁/绝望", List.of(
                "什么都没意思", "我是累赘", "每天哭", "活着很累", "没希望",
                "把东西送人"
        ));
        RISK_KEYWORDS.put("躯体化/进食睡眠", List.of(
                "吃不下", "暴食", "噩梦", "睡不着", "头痛", "肚子痛",
                "恶心", "胸闷"
        ));
        RISK_KEYWORDS.put("成瘾/网络沉迷", List.of(
                "停不下来", "通宵游戏", "偷钱充值", "烟", "酒", "药片"
        ));
    }

    @Override
    public RiskDetectionResult detect(String message) {
        if (message == null || message.isBlank()) {
            return RiskDetectionResult.safe();
        }

        String normalized = message.toLowerCase().trim();

        // 1. 检查否定/误报语境
        boolean hasNegation = NEGATION_PATTERN.matcher(normalized).find();
        boolean hasContext = CONTEXT_PATTERN.matcher(normalized).find();

        // 2. 红色硬规则检测（不可被否定降级）
        List<String> redMatches = matchKeywords(normalized, RED_HARD_KEYWORDS);
        if (!redMatches.isEmpty()) {
            log.warn("🚨 红色硬规则命中: keywords={}", redMatches);
            return new RiskDetectionResult(
                    RiskLevel.RED,
                    findCategory(redMatches),
                    redMatches,
                    85,
                    true,
                    "立即中断普通对话，进入安全响应，通知心理老师"
            );
        }

        // 3. 橙色关键词检测
        List<String> orangeMatches = matchKeywords(normalized, ORANGE_KEYWORDS);
        if (!orangeMatches.isEmpty()) {
            // 否定/引用语境下降为黄色（但性侵/虐待除外）
            if ((hasNegation || hasContext) && !isSensitiveCategory(orangeMatches)) {
                log.info("橙色关键词命中但含否定/引用语境，降为黄色: keywords={}", orangeMatches);
                return new RiskDetectionResult(
                        RiskLevel.YELLOW,
                        findCategory(orangeMatches),
                        orangeMatches,
                        35,
                        false,
                        "标记关注，允许继续对话，生成摘要给心理老师"
                );
            }
            log.warn("⚠️ 橙色风险命中: keywords={}", orangeMatches);
            return new RiskDetectionResult(
                    RiskLevel.ORANGE,
                    findCategory(orangeMatches),
                    orangeMatches,
                    60,
                    false,
                    "转人工队列，AI 只做稳定和求助引导"
            );
        }

        // 4. 黄色关键词检测
        List<String> yellowMatches = matchKeywords(normalized, YELLOW_KEYWORDS);
        if (!yellowMatches.isEmpty()) {
            if (hasNegation || hasContext) {
                return RiskDetectionResult.safe();
            }
            log.debug("黄色风险命中: keywords={}", yellowMatches);
            return new RiskDetectionResult(
                    RiskLevel.YELLOW,
                    findCategory(yellowMatches),
                    yellowMatches,
                    30,
                    false,
                    "允许继续 CBT 微干预，趋势观察"
            );
        }

        // 5. 无风险
        return RiskDetectionResult.safe();
    }

    /** 匹配关键词列表 */
    private List<String> matchKeywords(String text, List<String> keywords) {
        List<String> matches = new ArrayList<>();
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                matches.add(keyword);
            }
        }
        return matches;
    }

    /** 根据命中关键词查找所属风险类别 */
    private String findCategory(List<String> matchedKeywords) {
        for (Map.Entry<String, List<String>> entry : RISK_KEYWORDS.entrySet()) {
            for (String keyword : matchedKeywords) {
                if (entry.getValue().contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return "未分类";
    }

    /** 判断是否为不可降级的敏感类别（性侵/虐待） */
    private boolean isSensitiveCategory(List<String> matchedKeywords) {
        String category = findCategory(matchedKeywords);
        return category.contains("性侵") || category.contains("虐待");
    }
}
