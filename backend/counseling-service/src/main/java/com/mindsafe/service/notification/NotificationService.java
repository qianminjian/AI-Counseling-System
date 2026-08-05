package com.mindsafe.service.notification;

import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.RiskEvent;

import java.util.List;
import java.util.UUID;

/**
 * 通知服务（M1：站内消息）
 */
public interface NotificationService {

    /**
     * 风险事件触发通知（向同租户的心理老师 + 班主任发送）
     */
    void notifyRiskEvent(RiskEvent event);

    /**
     * 查询用户的通知列表（按创建时间倒序）
     */
    List<Notification> getNotifications(UUID recipientUserId, int limit);

    /**
     * 查询未读通知数量
     */
    long countUnread(UUID recipientUserId);

    /**
     * 标记通知为已读（P1 审计修复：必须携带收件人 ID，仅本人通知可标记，防 IDOR）
     */
    void markAsRead(UUID notificationId, UUID recipientUserId);
}
