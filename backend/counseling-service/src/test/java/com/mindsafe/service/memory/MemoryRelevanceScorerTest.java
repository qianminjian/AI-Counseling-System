package com.mindsafe.service.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryRelevanceScorer 单元测试（C2：召回阈值常量提取，锁定边界语义防漂移）
 */
class MemoryRelevanceScorerTest {

    @Test
    @DisplayName("C2: 召回阈值常量存在且为 0.3（低于阈值宁缺毋滥）")
    void recallThresholdConstant() {
        assertThat(MemoryRelevanceScorer.RECALL_THRESHOLD).isEqualTo(0.3);
    }

    @Test
    @DisplayName("C2: 边界行为锁定——等于阈值值得召回，低于阈值不召回")
    void recallBoundary() {
        MemoryRelevanceScorer scorer = new MemoryRelevanceScorer();
        assertThat(scorer.isWorthRecalling(MemoryRelevanceScorer.RECALL_THRESHOLD)).isTrue();
        assertThat(scorer.isWorthRecalling(MemoryRelevanceScorer.RECALL_THRESHOLD - 0.01)).isFalse();
    }
}
