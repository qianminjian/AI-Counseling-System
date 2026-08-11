package com.mindsafe.domain.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 消息摘要语义提炼器（D-7 路径 C，2026-07-28）
 * <p>
 * 原始设计需求（design/08 §5.1）：message_summaries 文本内容应为<b>提炼物</b>而非原文切片。
 * 本类以确定性规则实现常规消息的 ≤200 字语义提炼（零 LLM，成本可控；LLM 提炼登记远期）：
 * <ol>
 *   <li>按句切分（。！？；\n 为边界），去除句尾语气词；</li>
 *   <li>过滤纯语气词句与空句，连续重复句只保留一次；</li>
 *   <li>超出 200 字时，含情绪 / CBT 关键词的句子优先保留（稳定重排后截断）。</li>
 * </ol>
 * <p>
 * 风险消息（riskLevel ≥ ORANGE）不走本类，原文保真（安全证据 > 数据最小化），由调用方决策。
 */
public final class MessageSummarySummarizer {

    /** 提炼物最大长度（字符，design/08 §5.1 数据最小化口径） */
    public static final int MAX_SUMMARY_LENGTH = 200;

    /** 句尾语气词（去噪：哎呀/嗯嗯 等口语填充，语气词后可接句末标点/空白） */
    private static final Pattern TRAILING_PARTICLES = Pattern.compile(
            "[啊呀呢吧哦嗯哈啦嘛唉诶呐哟喽嘞]+[。！!？?；;~～\\s]*$");

    /** 纯语气词/噪声句（去尾后为空或仅剩语气词与标点） */
    private static final Pattern NOISE_ONLY = Pattern.compile(
            "^[啊呀呢吧哦嗯哈啦嘛唉诶呐哟喽嘞~～。！!？?\\s]*$");

    /** 情绪与 CBT 关键词（保留句优先，design/04 风险词库子集 + CBT 常用关联词） */
    private static final Set<String> KEYWORDS = Set.of(
            // 情绪词
            "难过", "伤心", "开心", "高兴", "害怕", "恐惧", "紧张", "焦虑", "生气", "愤怒",
            "烦躁", "委屈", "孤单", "孤独", "担心", "烦恼", "失望", "无助", "绝望", "哭",
            "烦", "闷", "慌", "讨厌", "喜欢", "心疼",
            // CBT 关键词
            "因为", "觉得", "认为", "总是", "每次", "从来", "不敢", "不想", "不愿",
            "应该", "必须", "怎么办", "为什么", "如果", "希望", "想要", "其实", "好像", "但是", "所以"
    );

    private MessageSummarySummarizer() {
    }

    /**
     * 语义提炼：返回 ≤200 字提炼物；null/空白输入原样返回（不抛异常）。
     */
    public static String summarize(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        // 1. 分句（lookbehind 保留句尾标点）
        String[] rawSentences = content.split("(?<=[。！？!?；;\\n])");

        // 2. 去噪：去句尾语气词 / 过滤纯噪声句 / 去重复
        List<String> cleaned = new ArrayList<>();
        String prev = null;
        for (String raw : rawSentences) {
            String s = cleanSentence(raw);
            if (s == null || s.isBlank() || s.equals(prev)) continue;
            prev = s;
            cleaned.add(s);
        }
        if (cleaned.isEmpty()) return "";

        // 3. 拼接；超长时关键词句优先（稳定排序，保持相对顺序），再截断
        String joined = String.join("", cleaned);
        if (joined.length() <= MAX_SUMMARY_LENGTH) {
            return joined;
        }
        cleaned.sort(Comparator.comparingInt((String s) -> containsKeyword(s) ? 0 : 1));
        StringBuilder sb = new StringBuilder();
        for (String s : cleaned) {
            sb.append(s);
        }
        // doing/92 R-021：按 code point 截断（UTF-16 substring 可能切断 emoji 代理对 → 显示 �）
        if (sb.length() <= MAX_SUMMARY_LENGTH) {
            return sb.toString();
        }
        return sb.substring(0, sb.offsetByCodePoints(0, MAX_SUMMARY_LENGTH));
    }

    /** 单句清理：去句尾语气词；整句为噪声（语气词/标点）时返回 null */
    private static String cleanSentence(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = TRAILING_PARTICLES.matcher(s).replaceFirst("");
        s = s.trim();
        if (s.isEmpty() || NOISE_ONLY.matcher(s).matches()) return null;
        return s;
    }

    private static boolean containsKeyword(String sentence) {
        for (String kw : KEYWORDS) {
            if (sentence.contains(kw)) return true;
        }
        return false;
    }
}
