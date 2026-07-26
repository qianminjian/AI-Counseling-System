package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 家长-学生关联（对应 tenant_template.parent_student_links）
 */
@TableName(value = "parent_student_links", schema = "tenant_template")
public class ParentStudentLink {

    @TableId(value = "link_id", type = IdType.INPUT)
    private UUID linkId;

    private UUID tenantId;
    private UUID parentId;
    private UUID studentUserId;
    private String relation;
    private Instant createdAt;

    public ParentStudentLink() {}

    // ===== Getters & Setters =====

    public UUID getLinkId() { return linkId; }
    public void setLinkId(UUID linkId) { this.linkId = linkId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
