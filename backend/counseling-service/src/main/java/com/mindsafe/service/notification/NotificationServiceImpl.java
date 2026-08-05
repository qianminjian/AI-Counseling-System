package com.mindsafe.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.NotificationMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通知服务实现（M1：站内消息，风险事件 → 教师通知）
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationServiceImpl(NotificationMapper notificationMapper, UserMapper userMapper,
                                   ApplicationEventPublisher eventPublisher) {
        this.notificationMapper = notificationMapper;
        this.userMapper = userMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 风险通知（P0-4 outbox 补偿改造）：
     * <ul>
     *   <li>REQUIRES_NEW：独立事务，DB 失败不影响主事务（risk_event 落库）；
     *       通知失败通过异常抛出，由调用方标记 notify_status=failed 进入补偿队列</li>
     *   <li>不再内部吞异常——静默失败即静默丢通知，由 outbox 状态机承接重试</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public void notifyRiskEvent(RiskEvent event) {
        // 查找同租户下所有教师（psych_teacher + class_teacher）
        List<User> teachers = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, event.getTenantId())
                        .in(User::getUserType, User.USER_TYPE_PSYCH_TEACHER, User.USER_TYPE_CLASS_TEACHER)
                        .eq(User::getStatus, User.STATUS_ACTIVE)
        );

        if (teachers.isEmpty()) {
            log.warn("租户 {} 无活跃教师，跳过通知", event.getTenantId());
            return;
        }

        String title = buildTitle(event);
        String body = buildBody(event);

        for (User teacher : teachers) {
            Notification notification = Notification.riskAlert(
                    event.getTenantId(),
                    teacher.getUserId(),
                    teacher.getUserType(),
                    title,
                    body,
                    event.getRiskEventId()
            );
            notification.setSeverity(event.getRiskLevel());
            notification.markSent(); // 站内消息立即可达
            notificationMapper.insert(notification);
        }

        log.info("风险通知已发送: riskEventId={}, 通知教师数={}", event.getRiskEventId(), teachers.size());

        // 发布 WebSocket 实时推送事件
        eventPublisher.publishEvent(new RiskAlertPushEvent(
                event.getTenantId(), event.getRiskEventId(), event.getStudentUserId(),
                event.getSourceId(),
                event.getRiskType(), event.getRiskLevel(),
                title, body, event.getDetectedAt()
        ));
    }

    @Override
    public List<Notification> getNotifications(UUID recipientUserId, int limit) {
        return notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getRecipientUserId, recipientUserId)
                        .orderByDesc(Notification::getCreatedAt)
                        .last("LIMIT " + Math.min(limit, 100))
        );
    }

    @Override
    public long countUnread(UUID recipientUserId) {
        return notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getRecipientUserId, recipientUserId)
                        .ne(Notification::getDeliveryStatus, "read")
        );
    }

    /**
     * 标记通知为已读（P1 审计修复：归属校验，防 IDOR）
     * <p>
     * 仅收件人本人可标记；他人通知或通知不存在 → 拒绝（IllegalArgumentException → 400）
     */
    @Override
    public void markAsRead(UUID notificationId, UUID recipientUserId) {
        Notification existing = notificationMapper.selectById(notificationId);
        if (existing == null || !recipientUserId.equals(existing.getRecipientUserId())) {
            throw new IllegalArgumentException("通知不存在: " + notificationId);
        }
        Notification update = new Notification();
        update.setNotificationId(notificationId);
        update.setDeliveryStatus("read");
        update.setReadAt(Instant.now());
        notificationMapper.updateById(update);
    }

    private String buildTitle(RiskEvent event) {
        String level = switch (event.getRiskLevel()) {
            case 3 -> "🔴 红色预警";
            case 2 -> "🟠 橙色预警";
            case 1 -> "🟡 黄色提醒";
            default -> "⚪ 风险提示";
        };
        return level + " - " + event.getRiskType();
    }

    private String buildBody(RiskEvent event) {
        return String.format("检测到学生心理风险信号（类型: %s，等级: %d），请及时关注。",
                event.getRiskType(), event.getRiskLevel());
    }
}
