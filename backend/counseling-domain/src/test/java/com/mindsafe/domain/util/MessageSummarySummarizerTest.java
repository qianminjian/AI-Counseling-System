package com.mindsafe.domain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageSummarySummarizer 单元测试（D-7 路径 C：常规消息语义提炼 ≤200 字）
 * <p>
 * 规则：去句尾语气词 / 过滤纯语气词句 / 去重复句 / 超长时含情绪与 CBT 关键词句优先。
 */
class MessageSummarySummarizerTest {

    @Test
    @DisplayName("null 与空白输入原样返回")
    void nullAndBlank() {
        assertNull(MessageSummarySummarizer.summarize(null));
        assertEquals("", MessageSummarySummarizer.summarize(""));
        assertEquals("  ", MessageSummarySummarizer.summarize("  "));
    }

    @Test
    @DisplayName("去除句尾语气词并过滤纯语气词句")
    void stripsTrailingParticles() {
        String result = MessageSummarySummarizer.summarize("嗯嗯。我今天考试没考好呀。");
        assertTrue(result.contains("我今天考试没考好"));
        assertFalse(result.contains("嗯嗯"));
        assertFalse(result.contains("呀"));
    }

    @Test
    @DisplayName("连续重复句只保留一次")
    void deduplicatesSentences() {
        String result = MessageSummarySummarizer.summarize("我很难过。我很难过。我很难过。");
        assertEquals("我很难过。", result);
    }

    @Test
    @DisplayName("保留含情绪词的句子（提炼物 ≠ 原文切片）")
    void keepsEmotionSentences() {
        String text = "我今天特别难过，因为好朋友不理我了。今天天气不错。";
        String result = MessageSummarySummarizer.summarize(text);
        assertTrue(result.contains("难过"));
        assertTrue(result.contains("好朋友"));
    }

    @Test
    @DisplayName("提炼结果不超过 200 字（长文本截断）")
    void maxLength200() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("今天是第").append(i + 1).append("天，普通的一天，没有特别的事情。");
        }
        String result = MessageSummarySummarizer.summarize(sb.toString());
        assertTrue(result.length() <= 200);
    }

    @Test
    @DisplayName("超长文本中情绪关键词句优先保留（窗口化提炼）")
    void keywordSentencesPrioritizedWhenOverflow() {
        // 40 句无关键词的填充句 + 1 句含"害怕"的关键句（排在最后）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("今天是第").append(i + 1).append("天，普通的一天，没有特别的事情。");
        }
        sb.append("但是晚上我一个人在家特别害怕。");
        String result = MessageSummarySummarizer.summarize(sb.toString());
        assertTrue(result.length() <= 200);
        assertTrue(result.contains("害怕"), "超长时含关键词句应被优先保留，实际结果: " + result);
    }

    @Test
    @DisplayName("短文本无噪音时原样保留（不误伤）")
    void shortTextKeptAsIs() {
        String text = "我和同桌吵架了，心情不好。";
        assertEquals(text, MessageSummarySummarizer.summarize(text));
    }
}
