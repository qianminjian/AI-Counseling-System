package com.mindsafe.service.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptEvalGovernance 单测（G-1 回归补测，design/45 §6/§8：灰度放量/自动回滚/κ 校准/人群下钻）
 */
class PromptEvalGovernanceTest {

    private final PromptEvalGovernance governance = new PromptEvalGovernance();

    // ===== 灰度放量 =====

    @Test
    void 护栏达标_可进入下一阶段() {
        PromptEvalGovernance.RolloutDecision d = governance.evaluateRollout(0, 0.95, 0.02, 0.02, 0.0);
        assertTrue(d.canAdvance());
        assertFalse(d.shouldRollback());
        assertEquals(5, d.currentStagePercent());
        assertEquals(20, d.nextStagePercent());
    }

    @Test
    void 已全量_不再放量() {
        PromptEvalGovernance.RolloutDecision d = governance.evaluateRollout(3, 0.95, 0.02, 0.02, 0.0);
        assertFalse(d.canAdvance());
        assertFalse(d.shouldRollback());
        assertEquals(100, d.currentStagePercent());
    }

    @Test
    void safety破线_触发回滚() {
        PromptEvalGovernance.RolloutDecision d = governance.evaluateRollout(
                1, PromptEvalGovernance.SAFETY_BREAK_THRESHOLD - 0.01, 0.02, 0.02, 0.0);
        assertTrue(d.shouldRollback());
        assertTrue(d.reason().contains("safety_compliance"));
    }

    @Test
    void 护栏拦截率突增_触发回滚() {
        PromptEvalGovernance.RolloutDecision d = governance.evaluateRollout(1, 0.95, 0.30, 0.05, 0.0);
        assertTrue(d.shouldRollback());
        assertTrue(d.reason().contains("拦截率"));
    }

    @Test
    void eval回退超容差_触发回滚() {
        PromptEvalGovernance.RolloutDecision d = governance.evaluateRollout(1, 0.95, 0.02, 0.02, -0.06);
        assertTrue(d.shouldRollback());
        assertTrue(d.reason().contains("eval"));
    }

    @Test
    void eval轻微回退在容差内_不回滚() {
        PromptEvalGovernance.RolloutDecision d = governance.evaluateRollout(1, 0.95, 0.02, 0.02, -0.04);
        assertFalse(d.shouldRollback());
    }

    // ===== κ 校准 =====

    @Test
    void kappa完全一致_可接受() {
        PromptEvalGovernance.KappaResult r = governance.computeKappa(
                new int[]{1, 1, 0, 0}, new int[]{1, 1, 0, 0}, "judge-v1");
        assertTrue(r.acceptable());
        assertEquals(4, r.sampleSize());
    }

    @Test
    void kappa低一致性_不可接受() {
        PromptEvalGovernance.KappaResult r = governance.computeKappa(
                new int[]{1, 1, 1, 1, 0, 0, 0, 0}, new int[]{0, 0, 1, 1, 1, 1, 0, 0}, "judge-v1");
        assertFalse(r.acceptable());
    }

    @Test
    void kappa空输入_不可接受() {
        assertFalse(governance.computeKappa(null, null, "v").acceptable());
        assertFalse(governance.computeKappa(new int[]{}, new int[]{}, "v").acceptable());
        assertFalse(governance.computeKappa(new int[]{1}, new int[]{1, 0}, "v").acceptable());
    }

    // ===== 人群下钻 =====

    @Test
    void 按年级段聚合四维均值() {
        List<PromptEvalGovernance.EvalRecord> records = List.of(
                new PromptEvalGovernance.EvalRecord("low", "anxious", "introvert", 0.8, 0.6, 0.9, 0.7),
                new PromptEvalGovernance.EvalRecord("low", "calm", "introvert", 0.6, 0.8, 1.0, 0.9),
                new PromptEvalGovernance.EvalRecord("high", "calm", "extrovert", 0.9, 0.9, 0.95, 0.8)
        );

        List<PromptEvalGovernance.EvalAggregate> aggs = governance.drillDown(
                records, PromptEvalGovernance.DrillDimension.GRADE_BAND);

        assertEquals(2, aggs.size());
        PromptEvalGovernance.EvalAggregate low = aggs.stream()
                .filter(a -> "low".equals(a.segment())).findFirst().orElseThrow();
        assertEquals(2, low.sampleCount());
        assertEquals(0.7, low.empathyMean(), 1e-9);
        assertEquals(0.95, low.safetyComplianceMean(), 1e-9);
    }

    @Test
    void 下钻维度为null分段归入unknown() {
        List<PromptEvalGovernance.EvalRecord> records = List.of(
                new PromptEvalGovernance.EvalRecord(null, "calm", "introvert", 0.5, 0.5, 0.5, 0.5));

        List<PromptEvalGovernance.EvalAggregate> aggs = governance.drillDown(
                records, PromptEvalGovernance.DrillDimension.GRADE_BAND);

        assertEquals("unknown", aggs.get(0).segment());
    }

    @Test
    void 空记录_返回空列表() {
        assertTrue(governance.drillDown(List.of(), PromptEvalGovernance.DrillDimension.ENTRY_MOOD).isEmpty());
        assertTrue(governance.drillDown(null, PromptEvalGovernance.DrillDimension.ENTRY_MOOD).isEmpty());
    }

    @Test
    void 分段显著低于整体_告警() {
        assertTrue(governance.isSegmentUnderperforming(0.6, 0.75));
        assertFalse(governance.isSegmentUnderperforming(0.7, 0.75));
    }
}
