package com.mindsafe.service.teacher;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.security.FieldEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 预警生命周期状态机（S-007②，doing/93）。
 * <p>
 * TeacherService 上帝类拆出的独立子域：预警从 open → claimed / false_positive →
 * resolved / follow_up_scheduled → closed 的状态流转 + 待回访查询收敛于此。
 * 状态语义（状态枚举值、回访字段联动、处理备注落库）集中在状态机内，
 * 可独立测试；不再散在上帝类的长方法列表中。
 */
@Service
public class AlertLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AlertLifecycleService.class);

    private final RiskEventMapper riskEventMapper;
    private final TeacherNoteStore teacherNoteStore;
    private final UserMapper userMapper;
    private final FieldEncryptionService fieldEncryptionService;

    public AlertLifecycleService(RiskEventMapper riskEventMapper, TeacherNoteStore teacherNoteStore,
                                 UserMapper userMapper, FieldEncryptionService fieldEncryptionService) {
        this.riskEventMapper = riskEventMapper;
        this.teacherNoteStore = teacherNoteStore;
        this.userMapper = userMapper;
        this.fieldEncryptionService = fieldEncryptionService;
    }

    /** 认领预警 */
    @Transactional
    public void claimAlert(UUID tenantId, UUID riskEventId, UUID teacherUserId) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus(RiskEvent.STATUS_CLAIMED);
        event.setAssignedUserId(teacherUserId);
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);
        log.info("预警已认领: riskEventId={}, teacher={}", riskEventId, teacherUserId);
    }

    /** 预警转派（目标教师同租户校验防跨租户泄露；转派备注加密落库） */
    @Transactional
    public void transferAlert(UUID tenantId, UUID riskEventId, UUID fromTeacherId,
                              UUID targetTeacherId, String note) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);

        // 目标教师必须存在且同租户（防止跨租户转派泄露学生数据）
        User target = userMapper.selectById(targetTeacherId);
        if (target == null || !tenantId.equals(target.getTenantId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "目标教师不存在: " + targetTeacherId);
        }

        event.setStatus(RiskEvent.STATUS_OPEN);
        event.setAssignedUserId(targetTeacherId);
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);

        if (note != null && !note.isBlank()) {
            TeacherNote transferNote = TeacherNote.create(
                    tenantId, event.getStudentUserId(), fromTeacherId,
                    fieldEncryptionService.encrypt("【预警转派】" + note), "transfer"
            );
            teacherNoteStore.insert(transferNote);
        }
        log.info("预警已转派: riskEventId={}, from={}, target={}", riskEventId, fromTeacherId, targetTeacherId);
    }

    /** 标记误报 */
    @Transactional
    public void markFalsePositive(UUID tenantId, UUID riskEventId, UUID teacherUserId) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus("false_positive");
        event.setAssignedUserId(teacherUserId);
        event.setClosedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);
        log.info("预警标记误报: riskEventId={}, teacher={}", riskEventId, teacherUserId);
    }

    /** 处理完成（线下干预后标记 resolved） */
    @Transactional
    public void resolveAlert(UUID tenantId, UUID riskEventId, UUID teacherUserId, String resolutionNote) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus(RiskEvent.STATUS_RESOLVED);
        event.setAssignedUserId(teacherUserId);
        event.setResolutionNote(resolutionNote);
        event.setResolvedAt(Instant.now());
        event.setClosedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);

        // 将处理记录存为教师备注（type=intervention）
        if (resolutionNote != null && !resolutionNote.isBlank()) {
            TeacherNote note = TeacherNote.create(
                    event.getTenantId(), event.getStudentUserId(), teacherUserId,
                    "【预警处理】" + resolutionNote, "intervention"
            );
            teacherNoteStore.insert(note);
        }

        log.info("预警已处理: riskEventId={}, teacher={}", riskEventId, teacherUserId);
    }

    /** DATA-004：安排回访（处置后不直接关闭，而是计划回访确认效果） */
    @Transactional
    public void scheduleFollowUp(UUID tenantId, UUID riskEventId, UUID teacherUserId, String followUpAtIso) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus("follow_up_scheduled");
        event.setAssignedUserId(teacherUserId);
        event.setFollowUpAt(Instant.parse(followUpAtIso));
        event.setFollowUpDone(false);
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);
        log.info("预警安排回访: riskEventId={}, followUpAt={}", riskEventId, followUpAtIso);
    }

    /** DATA-004：完成回访（填写回访记录 + 最终评估） */
    @Transactional
    public void completeFollowUp(UUID tenantId, UUID riskEventId, UUID teacherUserId,
                                 String followUpNote, String outcome) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus(RiskEvent.STATUS_CLOSED);
        event.setFollowUpDone(true);
        event.setFollowUpNote(followUpNote);
        event.setOutcome(outcome);
        event.setClosedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);

        // 回访记录存为教师备注
        if (followUpNote != null && !followUpNote.isBlank()) {
            TeacherNote note = TeacherNote.create(
                    event.getTenantId(), event.getStudentUserId(), teacherUserId,
                    "【回访记录】" + followUpNote, "follow_up"
            );
            teacherNoteStore.insert(note);
        }
        log.info("预警回访完成: riskEventId={}, outcome={}", riskEventId, outcome);
    }

    /** DATA-004：查询待回访事件列表 */
    public List<RiskEvent> getPendingFollowUps(UUID tenantId) {
        return riskEventMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .eq(RiskEvent::getFollowUpDone, false)
                        .isNotNull(RiskEvent::getFollowUpAt)
                        .orderByAsc(RiskEvent::getFollowUpAt)
        );
    }

    /** 租户校验：预警必须属于当前租户（防 IDOR 跨租户操作） */
    private RiskEvent getEventWithTenantCheck(UUID tenantId, UUID riskEventId) {
        RiskEvent event = riskEventMapper.selectById(riskEventId);
        if (event == null || !event.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.ALERT_NOT_FOUND, "预警不存在: " + riskEventId);
        }
        return event;
    }
}
