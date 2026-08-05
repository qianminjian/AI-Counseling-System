package com.mindsafe.service.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HybridRetrievalService 单元测试（C2：充分使用阈值常量提取，锁定边界语义防漂移）
 */
class HybridRetrievalServiceTest {

    private final HybridRetrievalService service = new HybridRetrievalService();

    @Test
    @DisplayName("C2: 充分使用阈值常量存在且为 0.7")
    void fullGroundednessThresholdConstant() {
        assertThat(HybridRetrievalService.FULL_GROUNDEDNESS_THRESHOLD).isEqualTo(0.7);
    }

    @Test
    @DisplayName("C2: 边界行为锁定——达到充分阈值判定充分利用")
    void fullGroundednessBoundary() {
        // 7/10 = 0.7 恰好达到充分使用阈值
        var result = service.evaluateGroundedness("s1", 10, 7);
        assertThat(result.feedback()).isEqualTo("检索内容被充分利用");
    }
}
