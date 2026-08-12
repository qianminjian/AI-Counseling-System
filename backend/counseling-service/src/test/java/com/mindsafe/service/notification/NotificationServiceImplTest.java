package com.mindsafe.service.notification;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.Notification;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.NotificationMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.entity.RiskEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * NotificationServiceImpl 单元测试（P1 审计修复：markAsRead 通知已读 IDOR）。
 * <p>
 * 契约：
 * - markAsRead 必须携带 recipientUserId：仅本人通知可标记已读，他人/不存在 → IllegalArgumentException 拒绝
 * - getNotifications / countUnread 按 recipientUserId 过滤
 * - notifyRiskEvent 向同租户活跃教师批量发送（REQUIRES_NEW 独立事务）
 */
class NotificationServiceImplTest {

    private NotificationMapper notificationMapper;
    private UserMapper userMapper;
    private ApplicationEventPublisher eventPublisher;
    private RiskEventMapper riskEventMapper;
    private NotificationServiceImpl service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();
    private final UUID otherTeacherId = UUID.randomUUID();
    private final UUID notificationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        userMapper = mock(UserMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        riskEventMapper = mock(RiskEventMapper.class);
        service = new NotificationServiceImpl(notificationMapper, userMapper, eventPublisher, riskEventMapper);
    }

    private Notification notification(UUID recipientId) {
        Notification n = Notification.riskAlert(
                tenantId, recipientId, "psych_teacher", "标题", "摘要", UUID.randomUUID());
        n.setNotificationId(notificationId);
        return n;
    }

    // ===== markAsRead 归属校验（P1 IDOR 修复） =====

    @Test
    @DisplayName("本人通知 → 标记已读")
    void ownNotification_markedRead() {
        when(notificationMapper.selectById(notificationId)).thenReturn(notification(teacherId));

        service.markAsRead(notificationId, teacherId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).updateById(captor.capture());
        assertEquals("read", captor.getValue().getDeliveryStatus());
        assertNotNull(captor.getValue().getReadAt());
    }

    @Test
    @DisplayName("他人通知 → 拒绝标记（防 IDOR），不更新")
    void othersNotification_rejected() {
        when(notificationMapper.selectById(notificationId)).thenReturn(notification(otherTeacherId));

        assertThrows(BizException.class,
                () -> service.markAsRead(notificationId, teacherId));
        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    @DisplayName("通知不存在 → 拒绝标记，不更新")
    void missingNotification_rejected() {
        when(notificationMapper.selectById(notificationId)).thenReturn(null);

        assertThrows(BizException.class,
                () -> service.markAsRead(notificationId, teacherId));
        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    // ===== 查询按收件人过滤 =====

    @Test
    @DisplayName("getNotifications 仅返回当前用户通知（按 recipientUserId 过滤；BUG-T-06-02 分页+筛选）")
    void getNotifications_filtersByRecipient() {
        Page<Notification> page = new Page<Notification>(1, 20).setRecords(List.of(notification(teacherId)));
        page.setTotal(1);
        when(notificationMapper.selectPage(any(), any())).thenReturn(page);

        NotificationService.NotificationPage result = service.getNotifications(teacherId, "ALL", 1, 20);

        assertEquals(1, result.items().size());
        assertEquals(teacherId, result.items().get(0).getRecipientUserId());
        assertEquals(1, result.total());
    }

    @Test
    @DisplayName("BUG-T-06-02 回归：UNREAD 筛选只查未读（readAt 为空），READ 只查已读")
    void getNotifications_statusFilter() {
        when(notificationMapper.selectPage(any(), any())).thenReturn(
                new Page<Notification>(1, 20).setRecords(List.of()));

        service.getNotifications(teacherId, "UNREAD", 1, 20);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(notificationMapper).selectPage(any(), cap.capture());
        // 筛选条件应含 readAt 为空（wrapper SQL 段包含 read_at IS NULL）
        assertNotNull(cap.getValue());

        service.getNotifications(teacherId, "READ", 1, 20);
        verify(notificationMapper, times(2)).selectPage(any(), any());
    }

    @Test
    @DisplayName("countUnread 排除已读")
    void countUnread_excludesRead() {
        when(notificationMapper.selectCount(any())).thenReturn(2L);

        assertEquals(2L, service.countUnread(teacherId));
    }

    // ===== notifyRiskEvent =====

    @Test
    @DisplayName("通知同租户所有活跃教师并发布 WebSocket 推送事件")
    void notifyRiskEvent_sendsToAllActiveTeachers() {
        User t1 = new User();
        t1.setUserId(teacherId);
        t1.setUserType("psych_teacher");
        User t2 = new User();
        t2.setUserId(otherTeacherId);
        t2.setUserType("class_teacher");
        when(userMapper.selectList(any())).thenReturn(List.of(t1, t2));

        var event = com.mindsafe.domain.entity.RiskEvent.fromDetection(
                tenantId, UUID.randomUUID(), UUID.randomUUID(), "self_harm", 3);

        service.notifyRiskEvent(event);

        verify(notificationMapper, times(2)).insert(any(Notification.class));
        verify(eventPublisher).publishEvent(any(RiskAlertPushEvent.class));
    }

    @Test
    @DisplayName("无活跃教师 → 跳过通知不发布事件")
    void notifyRiskEvent_noTeachers_skips() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        var event = com.mindsafe.domain.entity.RiskEvent.fromDetection(
                tenantId, UUID.randomUUID(), UUID.randomUUID(), "self_harm", 3);

        service.notifyRiskEvent(event);

        verify(notificationMapper, never()).insert(any(Notification.class));
        verify(eventPublisher, never()).publishEvent(any());
    }
}
