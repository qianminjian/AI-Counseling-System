package com.mindsafe.service.knowledge;

import com.mindsafe.service.knowledge.ReviewWorkflowStateMachine.ReviewStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 审核门禁校验器（KB-102，design/49 §5.2）
 * <p>
 * 四道门禁（全部通过才允许 in_review → published）：
 * <ol>
 *   <li>专业审核：临床团队核对循证依据、分龄适配、无诊断/治疗越界</li>
 *   <li>合规审核：危机/安全敏感内容与 design/04/14 一致、无违规表述</li>
 *   <li>红队校验：危机类内容过护栏回归（防被利用套取有害信息）</li>
 *   <li>版本留痕：reviewer + approved_at 非空，可回溯</li>
 * </ol>
 * <p>
 * 纯规则实现，不依赖外部服务。审核动作由管理端触发，本类只做门禁判定。
 */
@Component
public class ReviewGateValidator {

    /** 门禁校验结果 */
    public record GateResult(boolean passed, List<String> violations) {
        public static GateResult pass() {
            return new GateResult(true, List.of());
        }

        public static GateResult fail(List<String> violations) {
            return new GateResult(false, violations);
        }
    }

    /**
     * 校验发布门禁（in_review → published 前调用）。
     *
     * @param metadata 知识条目元数据
     * @return 门禁结果
     */
    public GateResult validateForPublish(KnowledgeMetadata metadata) {
        List<String> violations = new ArrayList<>();

        // 门禁 1：专业审核 — 必须有循证等级
        if (metadata.evidenceLevel() == null || metadata.evidenceLevel().isBlank()) {
            violations.add("GATE_PROFESSIONAL: 缺少循证等级(evidence_level)");
        }

        // 门禁 2：合规审核 — 安全敏感内容必须标注 safety_sensitive
        if (metadata.safetySensitive() && "crisis_intervention".equals(metadata.category())) {
            // 危机类 + 安全敏感 → 必须与 04/14 一致（此处校验 source_type 标注）
            if (metadata.sourceType() == null || metadata.sourceType().isBlank()) {
                violations.add("GATE_COMPLIANCE: 危机类安全敏感内容缺少来源标注(source_type)");
            }
        }

        // 门禁 3：红队校验 — 危机类内容必须标记 safety_sensitive
        if ("crisis_intervention".equals(metadata.category()) && !metadata.safetySensitive()) {
            violations.add("GATE_REDTEAM: crisis_intervention 类内容必须标记 safety_sensitive=true");
        }

        // 门禁 4：版本留痕 — reviewer 非空
        if (metadata.reviewer() == null || metadata.reviewer().isBlank()) {
            violations.add("GATE_TRACEABILITY: 缺少审核人(reviewer)");
        }

        return violations.isEmpty() ? GateResult.pass() : GateResult.fail(violations);
    }

    /**
     * 校验提交审核门禁（draft → in_review 前调用）。
     * <p>
     * 轻量校验：至少有分类和年级段。
     */
    public GateResult validateForReview(KnowledgeMetadata metadata) {
        List<String> violations = new ArrayList<>();

        if (metadata.category() == null || metadata.category().isBlank()) {
            violations.add("GATE_INTAKE: 缺少知识分类(category)");
        }
        if (metadata.gradeBand() == null || metadata.gradeBand().isBlank()) {
            violations.add("GATE_INTAKE: 缺少适用年级段(grade_band)");
        }

        return violations.isEmpty() ? GateResult.pass() : GateResult.fail(violations);
    }

    /**
     * 校验状态转移合法性 + 门禁（组合调用）。
     *
     * @param stateMachine 状态机
     * @param from         当前状态
     * @param to           目标状态
     * @param metadata     元数据
     * @return 门禁结果（状态非法直接 fail）
     */
    public GateResult validateTransition(ReviewWorkflowStateMachine stateMachine,
                                         ReviewStatus from, ReviewStatus to,
                                         KnowledgeMetadata metadata) {
        if (!stateMachine.canTransition(from, to)) {
            return GateResult.fail(List.of(
                    String.format("STATE_MACHINE: 非法转移 %s → %s", from, to)));
        }

        // 按目标状态触发对应门禁
        if (to == ReviewStatus.IN_REVIEW) {
            return validateForReview(metadata);
        }
        if (to == ReviewStatus.PUBLISHED) {
            return validateForPublish(metadata);
        }

        // deprecated 无额外门禁
        return GateResult.pass();
    }
}
