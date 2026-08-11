package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知实体（对应 tenant_template.notifications）
 */
@TableName(value = "notifications", schema = TenantSchema.TENANT_TEMPLATE)
public class Notification {

    @TableId(value = "notification_id", type = IdType.INPUT)
    private UUID notificationId;

    private UUID tenantId;
    private UUID schoolId;
    private UUID recipientUserId;
    private String recipientRole;
    private String channel;
    private String templateCode;
    private Integer severity;
    private String title;
    private String bodySummary;
    private String relatedType;
    private UUID relatedId;
    private String deliveryStatus;
    private Instant sentAt;
    private Instant readAt;
    private Instant createdAt;

    public Notification() {
    }

    public static Notification riskAlert(UUID tenantId, UUID recipientUserId, String recipientRole,
                                         String title, String bodySummary, UUID riskEventId) {
        Notification n = new Notification();
        n.notificationId = UUID.randomUUID();
        n.tenantId = tenantId;
        n.recipientUserId = recipientUserId;
        n.recipientRole = recipientRole;
        n.channel = "in_app";
        n.templateCode = "risk_alert";
        n.severity = 3;
        n.title = title;
        n.bodySummary = bodySummary;
        n.relatedType = "risk_event";
        n.relatedId = riskEventId;
        n.deliveryStatus = "pending";
        n.createdAt = Instant.now();
        return n;
    }

    public void markSent() {
        this.deliveryStatus = "sent";
        this.sentAt = Instant.now();
    }

    public void markRead() {
        this.deliveryStatus = "read";
        this.readAt = Instant.now();
    }

    // ===== Getters & Setters =====

    public UUID getNotificationId() { return notificationId; }
    public void setNotificationId(UUID notificationId) { this.notificationId = notificationId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getSchoolId() { return schoolId; }
    public void setSchoolId(UUID schoolId) { this.schoolId = schoolId; }

    public UUID getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(UUID recipientUserId) { this.recipientUserId = recipientUserId; }

    public String getRecipientRole() { return recipientRole; }
    public void setRecipientRole(String recipientRole) { this.recipientRole = recipientRole; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }

    public Integer getSeverity() { return severity; }
    public void setSeverity(Integer severity) { this.severity = severity; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBodySummary() { return bodySummary; }
    public void setBodySummary(String bodySummary) { this.bodySummary = bodySummary; }

    public String getRelatedType() { return relatedType; }
    public void setRelatedType(String relatedType) { this.relatedType = relatedType; }

    public UUID getRelatedId() { return relatedId; }
    public void setRelatedId(UUID relatedId) { this.relatedId = relatedId; }

    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String deliveryStatus) { this.deliveryStatus = deliveryStatus; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
