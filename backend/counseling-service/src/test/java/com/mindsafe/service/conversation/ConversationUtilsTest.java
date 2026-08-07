package com.mindsafe.service.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConversationUtils 单元测试（DC-008，doing/72 §22）
 * <p>
 * 覆盖：classifyStudentMessage 轻微倾诉判定（DISTRESS_EMOTIONS 收编后
 * scared/nervous 语义不丢）；重/敷衍/普通分支回归锚点。
 */
class ConversationUtilsTest {

    @Nested
    @DisplayName("classifyStudentMessage 消息分类")
    class ClassifyStudentMessage {

        @ParameterizedTest
        @ValueSource(strings = {"sad", "angry", "scared", "nervous"})
        @DisplayName("负面情绪 + 有内容 → 轻微倾诉（DC-008：scared/nervous 收编后仍判负面）")
        void negativeEmotionDisclosure(String emotionTag) {
            assertThat(ConversationUtils.classifyStudentMessage("今天没人和我玩", false, emotionTag))
                    .isEqualTo(NudgeDecisionModel.MSG_DISCLOSURE);
        }

        @Test
        @DisplayName("非负面情绪 → 普通消息")
        void happyEmotionNormal() {
            assertThat(ConversationUtils.classifyStudentMessage("今天体育课很好玩", false, "happy"))
                    .isEqualTo(NudgeDecisionModel.MSG_NORMAL);
        }

        @Test
        @DisplayName("情绪缺失 → 普通消息")
        void nullEmotionNormal() {
            assertThat(ConversationUtils.classifyStudentMessage("今天体育课很好玩", false, null))
                    .isEqualTo(NudgeDecisionModel.MSG_NORMAL);
        }

        @Test
        @DisplayName("命中风险信号 → 沉重倾诉（优先于情绪判定）")
        void riskyHeavy() {
            assertThat(ConversationUtils.classifyStudentMessage("我想死", true, "sad"))
                    .isEqualTo(NudgeDecisionModel.MSG_HEAVY);
        }

        @Test
        @DisplayName("敷衍短答 → 敷衍回答")
        void perfunctoryReply() {
            assertThat(ConversationUtils.classifyStudentMessage("嗯", false, null))
                    .isEqualTo(NudgeDecisionModel.MSG_PERFUNCTORY);
        }

        @Test
        @DisplayName("负面情绪但内容过短 → 普通消息（轻微倾诉需有内容）")
        void negativeEmotionTooShortNormal() {
            assertThat(ConversationUtils.classifyStudentMessage("难受", false, "sad"))
                    .isEqualTo(NudgeDecisionModel.MSG_NORMAL);
        }
    }
}
