package com.mindsafe.ai.risk;

import java.util.Map;
import java.util.Set;

/**
 * 情绪知识单一规则源（ARCH-003，doing/63 §3.1/§4.2）。
 * <p>
 * 只读深模块：负面/正面情绪权威成员表（含中英别名）+ 唯一判定入口。
 * 消费点只引用不定义（6 处负面情绪集合收敛于此），解决「同一信号（如 anxious）
 * 在不同管线结论不同」的漏判隐患。
 * <p>
 * 收编来源（2026-08-06 调研核对）：
 * <ul>
 *   <li>SessionState L125（sad/fearful/angry/disgusted）</li>
 *   <li>ConversationRiskProcessor L302-305（同左）</li>
 *   <li>SessionEndAnalyticsService L146-149（+anxious/crisis）</li>
 *   <li>VoiceEmotionTrendAnalyzer L47-49（+anxious/crisis）</li>
 *   <li>ConversationContextAgent L289-294（+anxious/withdrawn）</li>
 *   <li>LongTermMemoryService L390-397（contains 子串 + 中文）</li>
 * </ul>
 * <p>
 * 设计铁律：静态只读、无状态无副作用；权威成员表增删唯一入口即本类。
 */
public final class EmotionVocabulary {

    private EmotionVocabulary() {
    }

    /** 情绪分类 */
    public enum Category {
        NEGATIVE, POSITIVE, UNKNOWN
    }

    /** 权威负面成员（英文 SER 规范集；anxious 全管线一致 = doing/63 核心修复点）
     *  DC-008（doing/72 §22）：增补 scared/nervous（系统真实码值且语义负面，
     *  DISTRESS_EMOTIONS 独立成员集收编后不丢判定） */
    public static final Set<String> NEGATIVE_KEYS = Set.of(
            "sad", "fearful", "angry", "anxious", "disgusted", "withdrawn",
            "crisis", "lonely", "scared", "nervous"
    );

    /** 权威正面成员（英文 SER 规范集） */
    public static final Set<String> POSITIVE_KEYS = Set.of(
            "happy", "calm", "relieved", "hopeful", "neutral"
    );

    /** 负面中文别名（精确词或作为子串出现均判定为负面） */
    public static final Set<String> NEGATIVE_CHINESE = Set.of(
            "难过", "悲伤", "生气", "愤怒", "害怕", "恐惧", "焦虑", "厌恶", "退缩", "孤独", "危机", "紧张"
    );

    /** 正面中文别名 */
    public static final Set<String> POSITIVE_CHINESE = Set.of(
            "开心", "高兴", "平静", "放松", "希望"
    );

    /**
     * 负面英文子串模式（LongTermMemoryService 原 contains 语义全收编：
     * sad/angry/fear/anxious/lonely/crisis，2026-08-06 调研核对补全 sad/angry/anxious）
     */
    public static final Set<String> NEGATIVE_SUBSTRINGS = Set.of(
            "sad", "angry", "fear", "anxious", "lonely", "crisis"
    );

    /** 负面中文单字子串模式（LongTermMemoryService 原 contains 语义：悲/怒/惧/焦/孤） */
    public static final Set<String> NEGATIVE_CHINESE_SUBSTRINGS = Set.of(
            "悲", "怒", "惧", "焦", "孤"
    );

    /** 正面英文子串模式（组合文本判定用） */
    public static final Set<String> POSITIVE_SUBSTRINGS = Set.of(
            "happy", "calm", "relieved", "hopeful", "neutral"
    );

    /**
     * 情绪分类判定（消费点统一入口）。
     * <p>
     * 判定顺序：英文精确成员 → 中文别名（contains）→ 英文子串 → 中文单字子串 → UNKNOWN。
     * 组合文本（如 "sad + lonely"、"feels fearful today"）经子串路径判定，
     * 兼容 LongTermMemoryService 记忆回注场景。
     *
     * @param keyOrText 情绪 key（sad/anxious/...）或文本（记忆回注等场景）
     * @return NEGATIVE / POSITIVE / UNKNOWN（null/空白 → UNKNOWN）
     */
    public static Category classify(String keyOrText) {
        if (keyOrText == null || keyOrText.isBlank()) {
            return Category.UNKNOWN;
        }
        String lower = keyOrText.toLowerCase();

        // 1. 英文精确成员
        if (NEGATIVE_KEYS.contains(lower)) {
            return Category.NEGATIVE;
        }
        if (POSITIVE_KEYS.contains(lower)) {
            return Category.POSITIVE;
        }

        // 2. 中文别名（包含匹配，覆盖 "有点悲伤" 等组合文本）
        if (containsAny(keyOrText, NEGATIVE_CHINESE)) {
            return Category.NEGATIVE;
        }
        if (containsAny(keyOrText, POSITIVE_CHINESE)) {
            return Category.POSITIVE;
        }

        // 3. 英文子串（LongTermMemoryService 原 contains 语义）
        if (containsAny(lower, NEGATIVE_SUBSTRINGS)) {
            return Category.NEGATIVE;
        }
        if (containsAny(lower, POSITIVE_SUBSTRINGS)) {
            return Category.POSITIVE;
        }

        // 4. 中文单字子串（原 contains("悲"/"怒"/"惧"/"焦"/"孤") 语义）
        if (containsAny(keyOrText, NEGATIVE_CHINESE_SUBSTRINGS)) {
            return Category.NEGATIVE;
        }

        return Category.UNKNOWN;
    }

    /**
     * 是否为权威成员（精确判断：英文 key 或中文别名，不包含子串/组合文本）。
     *
     * @param key 情绪 key
     * @return 是权威成员（负面或正面）返回 true
     */
    public static boolean contains(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase();
        return NEGATIVE_KEYS.contains(lower)
                || POSITIVE_KEYS.contains(lower)
                || NEGATIVE_CHINESE.contains(key)
                || POSITIVE_CHINESE.contains(key);
    }

    /**
     * 负面判定（消费点统一入口，替代各文件内嵌 isNegative/isNegativeEmotion）。
     *
     * @param keyOrText 情绪 key 或文本
     * @return 负面返回 true
     */
    public static boolean isNegative(String keyOrText) {
        return classify(keyOrText) == Category.NEGATIVE;
    }

    // ===== 中文展示标签（DC-008，doing/72 §22：anxious 全系统单译，五处收敛为一） =====

    /**
     * 展示标签表（儿童友好主场景：anxious→紧张；教师端同源）。
     * 覆盖 SER + 展示全码值（含 scared/nervous/tired/withdrawn/lonely/crisis 等）。
     */
    public static final Map<String, String> ZH_LABELS = Map.ofEntries(
            Map.entry("happy", "开心"), Map.entry("sad", "难过"), Map.entry("angry", "生气"),
            Map.entry("scared", "害怕"), Map.entry("fearful", "恐惧"), Map.entry("nervous", "紧张"),
            Map.entry("anxious", "紧张"), Map.entry("neutral", "平静"), Map.entry("calm", "平静"),
            Map.entry("excited", "兴奋"), Map.entry("surprised", "惊讶"), Map.entry("disgusted", "厌恶"),
            Map.entry("tired", "疲惫"), Map.entry("withdrawn", "沉默"), Map.entry("lonely", "孤独"),
            Map.entry("crisis", "危机"));

    /**
     * 情绪码值 → 中文展示标签（消费点统一入口，替代各文件本地 EMOTION_LABELS/EMOTION_ZH）。
     *
     * @param code 情绪码值（英文 key）
     * @return 中文标签；null/空白 → ""；未知码值原样返回
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return ZH_LABELS.getOrDefault(code, code);
    }

    private static boolean containsAny(String text, Set<String> candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
