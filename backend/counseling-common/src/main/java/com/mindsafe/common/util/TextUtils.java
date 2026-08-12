package com.mindsafe.common.util;

/**
 * 通用文本工具（BA-15：LLM 输出代码围栏剥离收敛公共工具）
 * <p>
 * 原 OutputReviewService / MessageSummaryService 双处同构复制（含围栏剥离），
 * 行为已核实一致，收敛至此单点，消除双处漂移。
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * 剥离 LLM 可能包裹的 markdown 代码围栏（```json ... ```）。
     *
     * @param raw 原始 LLM 输出（可为 null）
     * @return 剥离围栏并 trim 后的内容；null 输入返回 null
     */
    public static String stripCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        return s;
    }

    /**
     * 按 Unicode code point 截断到 maxLen 个字符（R-021：UTF-16 substring 可能劈开
     * emoji/生僻字代理对 → 显示为 �；字段级加密后解密可能损坏摘要）。
     *
     * @param s      原始文本（可为 null）
     * @param maxLen 最大字符数（code point 口径，须 ≥ 0）
     * @return 截断后的文本；null 输入返回 null；不足 maxLen 原样返回
     */
    public static String truncateByCodePoint(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxLen));
    }
}
