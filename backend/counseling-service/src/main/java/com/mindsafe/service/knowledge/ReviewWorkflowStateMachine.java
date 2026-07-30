package com.mindsafe.service.knowledge;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 知识审核工作流状态机（KB-102，design/49 §五）
 * <p>
 * 状态流转：
 * <pre>
 * draft（采编）→ in_review（审核中）→ published（可检索）
 *                    │                      │
 *                    ↓（驳回）               ↓（过时/规则变更）
 *                  draft                  deprecated（下线）
 * </pre>
 * <p>
 * 铁律：
 * <ul>
 *   <li>仅 published 内容可被 RAG 检索</li>
 *   <li>deprecated 内容不可恢复为 published（需重新走审核）</li>
 *   <li>状态转移必须经门禁校验（{@link ReviewGateValidator}）</li>
 * </ul>
 */
@Component
public class ReviewWorkflowStateMachine {

    /** 审核状态枚举 */
    public enum ReviewStatus {
        DRAFT, IN_REVIEW, PUBLISHED, DEPRECATED
    }

    /** 合法转移表 */
    private static final EnumSet<ReviewStatus> FROM_DRAFT = EnumSet.of(ReviewStatus.IN_REVIEW);
    private static final EnumSet<ReviewStatus> FROM_IN_REVIEW = EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.DRAFT);
    private static final EnumSet<ReviewStatus> FROM_PUBLISHED = EnumSet.of(ReviewStatus.DEPRECATED);
    private static final EnumSet<ReviewStatus> FROM_DEPRECATED = EnumSet.noneOf(ReviewStatus.class);

    /**
     * 判断状态转移是否合法。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true=合法转移
     */
    public boolean canTransition(ReviewStatus from, ReviewStatus to) {
        if (from == null || to == null) return false;
        return allowedTargets(from).contains(to);
    }

    /**
     * 执行状态转移（校验合法性，非法则抛异常）。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 目标状态
     * @throws IllegalStateException 非法转移
     */
    public ReviewStatus transition(ReviewStatus from, ReviewStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    String.format("非法状态转移: %s → %s（允许: %s）", from, to, allowedTargets(from)));
        }
        return to;
    }

    /**
     * 获取给定状态的合法目标集。
     */
    public Set<ReviewStatus> allowedTargets(ReviewStatus from) {
        if (from == null) return EnumSet.noneOf(ReviewStatus.class);
        return switch (from) {
            case DRAFT -> FROM_DRAFT;
            case IN_REVIEW -> FROM_IN_REVIEW;
            case PUBLISHED -> FROM_PUBLISHED;
            case DEPRECATED -> FROM_DEPRECATED;
        };
    }

    /**
     * 判断内容是否可被 RAG 检索（仅 published）。
     */
    public boolean isSearchable(ReviewStatus status) {
        return status == ReviewStatus.PUBLISHED;
    }

    /**
     * 从数据库 status 字符串映射到枚举（兼容现有 'active' 值）。
     * <p>
     * 现有数据 status='active' 视为 PUBLISHED（历史语料已人工审定）。
     */
    public static ReviewStatus fromDbStatus(String dbStatus) {
        if (dbStatus == null) return ReviewStatus.DRAFT;
        return switch (dbStatus.toLowerCase()) {
            case "active", "published" -> ReviewStatus.PUBLISHED;
            case "in_review" -> ReviewStatus.IN_REVIEW;
            case "deprecated" -> ReviewStatus.DEPRECATED;
            default -> ReviewStatus.DRAFT;
        };
    }

    /**
     * 枚举转数据库存储值。
     */
    public static String toDbStatus(ReviewStatus status) {
        return status.name().toLowerCase();
    }
}
