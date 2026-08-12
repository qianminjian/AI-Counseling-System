package com.mindsafe.service.knowledge;

import java.time.Instant;

/**
 * 知识条目增强元数据（KB-102，design/49 §4.2）
 * <p>
 * 在现有 knowledge_documents 表 category 基础上补充的元数据字段。
 * 当前以 record 形式在服务层流转，DDL 扩展待数据库变更审批后落地。
 * <p>
 * 字段说明：
 * <ul>
 *   <li>gradeBand: 适用年级段（low=1-2 / mid=3-4 / high=5-6 / all=全年级）</li>
 *   <li>sourceType: 来源类型（textbook/official/clinical_authored/design_doc）</li>
 *   <li>evidenceLevel: 循证等级（high/medium/reference）</li>
 *   <li>reviewStatus: 审核状态（由状态机管理）</li>
 *   <li>version: 版本号（每次审核通过递增）</li>
 *   <li>reviewer: 审核人标识</li>
 *   <li>approvedAt: 最近审核通过时间</li>
 *   <li>safetySensitive: 是否危机/安全敏感（crisis_intervention 类强制 true）</li>
 * </ul>
 *
 * @param docId           文档 ID（字符串形式，兼容现有 UUID）
 * @param category        知识分类（5 类）
 * @param gradeBand       适用年级段
 * @param sourceType      来源类型
 * @param evidenceLevel   循证等级
 * @param reviewStatus    审核状态
 * @param version         版本号
 * @param reviewer        审核人
 * @param approvedAt      审核通过时间
 * @param safetySensitive 安全敏感标记
 */
public record KnowledgeMetadata(
        String docId,
        String category,
        String gradeBand,
        String sourceType,
        String evidenceLevel,
        ReviewWorkflowStateMachine.ReviewStatus reviewStatus,
        int version,
        String reviewer,
        Instant approvedAt,
        boolean safetySensitive
) {

    /**
     * 创建草稿元数据（初始状态）。
     */
    public static KnowledgeMetadata draft(String docId, String category, String gradeBand) {
        return new KnowledgeMetadata(docId, category, gradeBand,
                null, null, ReviewWorkflowStateMachine.ReviewStatus.DRAFT,
                0, null, null, "crisis_intervention".equals(category));
    }
}
