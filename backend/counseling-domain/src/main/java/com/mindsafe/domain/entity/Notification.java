package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知实体（对应 tenant_template.notifications）
 * <p>
 * 状态语义（P1-2，板块06 收敛；对齐板块 05 P1-1 未读单口径）：
 * <ul>
 *   <li>{@code deliveryStatus} 仅表达<b>投递态</b>（{@link #DELIVERY_PENDING} 待投递 / {@link #DELIVERY_SENT} 已投递）；
 *       历史存量 {@code "read"} 值不再产生（兼容读）</li>
 *   <li>{@code readAt} 为<b>唯一已读权威</b>：已读判定/未读统计一律 {@code readAt} 是否为空（countUnread 与
 *       列表 UNREAD 筛选单一口径），{@link #markRead()} 不再改 deliveryStatus</li>
 * </ul>
 */
@TableName(value = "notifications", schema = TenantSchema.TENANT_TEMPLATE)
public class Notification {

    /** C2 收敛（P1-2，板块06）：投递态——待投递 */
    public static final String DELIVERY_PENDING = "pending";

    /** C2 收敛（P1-2，板块06）：投递态——已投递（站内消息立即可达） */
    public static final String DELIVERY_SENT = "sent";

    /** 存量兼容值：已读（历史数据；语义收敛后不再产生——readAt 为唯一已读权威） */
    public static final String DELIVERY_READ = "read";

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

    /** BUG-T-06-03（2026-08-12）：学生昵称（列表展示用，非表字段，service 层关联填充） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String studentNickname;

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
        n.deliveryStatus = DELIVERY_PENDING;
        n.createdAt = Instant.now();
        return n;
    }

    public void markSent() {
        this.deliveryStatus = DELIVERY_SENT;
        this.sentAt = Instant.now();
    }

    /**
     * 标记已读（P1-2 板块06 语义收敛：readAt 为唯一已读权威，
     * deliveryStatus 仅表达投递态，已读不再改投递态——历史存量 read 值不再产生）。
     */
    public void markRead() {
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

    public String getStudentNickname() { return studentNickname; }
    public void setStudentNickname(String studentNickname) { this.studentNickname = studentNickname; }
}
