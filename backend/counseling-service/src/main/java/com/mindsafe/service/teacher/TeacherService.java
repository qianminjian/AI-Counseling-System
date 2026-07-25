package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.*;
import com.mindsafe.domain.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 教师端服务（对齐 design/16 §4 教师端 API）
 * <p>
 * 功能：工作台概览 / 预警队列 / 认领&误报 / 学生档案 / 备注管理
 */
@Service
public class TeacherService {

    private static final Logger log = LoggerFactory.getLogger(TeacherService.class);

    private final RiskEventMapper riskEventMapper;
    private final CounselingSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final TeacherNoteMapper teacherNoteMapper;
    private final NotificationMapper notificationMapper;

    public TeacherService(RiskEventMapper riskEventMapper,
                          CounselingSessionMapper sessionMapper,
                          UserMapper userMapper,
                          TeacherNoteMapper teacherNoteMapper,
                          NotificationMapper notificationMapper) {
        this.riskEventMapper = riskEventMapper;
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.teacherNoteMapper = teacherNoteMapper;
        this.notificationMapper = notificationMapper;
    }

    // ===== 工作台概览 =====

    public DashboardVO getDashboard(UUID tenantId, UUID teacherUserId) {
        Instant now = Instant.now();
        Instant todayStart = now.truncatedTo(ChronoUnit.DAYS);
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);

        // 待处理预警数
        long pendingAlerts = riskEventMapper.selectCount(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .eq(RiskEvent::getStatus, "open")
        );

        // 今日新增预警
        long todayAlerts = riskEventMapper.selectCount(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .ge(RiskEvent::getDetectedAt, todayStart)
        );

        // 今日活跃会话数
        long todaySessions = sessionMapper.selectCount(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, todayStart)
        );

        // 周趋势（最近 7 天每天的风险事件数）
        List<DailyCount> weeklyTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            Instant dayStart = now.minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            long count = riskEventMapper.selectCount(
                    new LambdaQueryWrapper<RiskEvent>()
                            .eq(RiskEvent::getTenantId, tenantId)
                            .ge(RiskEvent::getDetectedAt, dayStart)
                            .lt(RiskEvent::getDetectedAt, dayEnd)
            );
            weeklyTrend.add(new DailyCount(dayStart.toString().substring(0, 10), count));
        }

        return new DashboardVO(pendingAlerts, todayAlerts, todaySessions, weeklyTrend);
    }

    // ===== 预警队列 =====

    public List<AlertVO> getAlerts(UUID tenantId, String status, Integer minLevel, int limit) {
        LambdaQueryWrapper<RiskEvent> wrapper = new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getTenantId, tenantId);

        if (status != null && !status.isBlank()) {
            wrapper.eq(RiskEvent::getStatus, status);
        }
        if (minLevel != null) {
            wrapper.ge(RiskEvent::getRiskLevel, minLevel);
        }
        wrapper.orderByDesc(RiskEvent::getRiskLevel)
                .orderByDesc(RiskEvent::getDetectedAt)
                .last("LIMIT " + Math.min(limit, 100));

        List<RiskEvent> events = riskEventMapper.selectList(wrapper);

        return events.stream().map(e -> {
            // 查询学生信息
            User student = userMapper.selectById(e.getStudentUserId());
            String studentName = student != null ? student.getPseudonym() : "未知学生";
            return new AlertVO(
                    e.getRiskEventId(), e.getStudentUserId(), studentName,
                    e.getRiskType(), e.getRiskLevel(), e.getStatus(),
                    e.getDetectedAt(), e.getAssignedUserId()
            );
        }).toList();
    }

    /** 认领预警 */
    public void claimAlert(UUID riskEventId, UUID teacherUserId) {
        RiskEvent event = riskEventMapper.selectById(riskEventId);
        if (event == null) {
            throw new IllegalArgumentException("预警不存在: " + riskEventId);
        }
        event.setStatus("claimed");
        event.setAssignedUserId(teacherUserId);
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);
        log.info("预警已认领: riskEventId={}, teacher={}", riskEventId, teacherUserId);
    }

    /** 标记误报 */
    public void markFalsePositive(UUID riskEventId, UUID teacherUserId) {
        RiskEvent event = riskEventMapper.selectById(riskEventId);
        if (event == null) {
            throw new IllegalArgumentException("预警不存在: " + riskEventId);
        }
        event.setStatus("false_positive");
        event.setAssignedUserId(teacherUserId);
        event.setClosedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);
        log.info("预警标记误报: riskEventId={}, teacher={}", riskEventId, teacherUserId);
    }

    // ===== 学生档案 =====

    public StudentProfileVO getStudentProfile(UUID tenantId, UUID studentUserId) {
        User student = userMapper.selectById(studentUserId);
        if (student == null) {
            throw new IllegalArgumentException("学生不存在: " + studentUserId);
        }

        // 最近会话
        List<CounselingSession> recentSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .orderByDesc(CounselingSession::getStartedAt)
                        .last("LIMIT 10")
        );

        // 预警历史
        List<RiskEvent> riskHistory = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .eq(RiskEvent::getStudentUserId, studentUserId)
                        .orderByDesc(RiskEvent::getDetectedAt)
                        .last("LIMIT 20")
        );

        // 教师备注
        List<TeacherNote> notes = teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .orderByDesc(TeacherNote::getCreatedAt)
        );

        // 最高风险等级
        int maxRisk = riskHistory.stream()
                .mapToInt(RiskEvent::getRiskLevel)
                .max().orElse(0);

        return new StudentProfileVO(
                student.getUserId(), student.getPseudonym(),
                student.getGradeCode(), student.getClassCode(),
                maxRisk, recentSessions.size(),
                recentSessions.stream().map(s -> new SessionSummaryVO(
                        s.getSessionId(), s.getStartedAt(), s.getSessionStatus(),
                        s.getRiskLevelSnapshot()
                )).toList(),
                riskHistory.stream().map(e -> new AlertVO(
                        e.getRiskEventId(), e.getStudentUserId(), student.getPseudonym(),
                        e.getRiskType(), e.getRiskLevel(), e.getStatus(),
                        e.getDetectedAt(), e.getAssignedUserId()
                )).toList(),
                notes.stream().map(n -> new NoteVO(
                        n.getNoteId(), n.getTeacherUserId(), n.getContent(),
                        n.getNoteType(), n.getCreatedAt()
                )).toList()
        );
    }

    /** 高风险学生列表 */
    public List<HighRiskStudentVO> getHighRiskStudents(UUID tenantId) {
        // 查询所有 open/claimed 状态的风险事件，按学生分组取最高风险
        List<RiskEvent> openEvents = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .in(RiskEvent::getStatus, "open", "claimed")
                        .orderByDesc(RiskEvent::getRiskLevel)
        );

        // 按学生分组
        Map<UUID, List<RiskEvent>> byStudent = openEvents.stream()
                .collect(Collectors.groupingBy(RiskEvent::getStudentUserId));

        return byStudent.entrySet().stream().map(entry -> {
            UUID studentId = entry.getKey();
            List<RiskEvent> events = entry.getValue();
            int maxRisk = events.stream().mapToInt(RiskEvent::getRiskLevel).max().orElse(0);
            Instant latestEvent = events.stream()
                    .map(RiskEvent::getDetectedAt)
                    .max(Instant::compareTo).orElse(Instant.now());

            User student = userMapper.selectById(studentId);
            String name = student != null ? student.getPseudonym() : "未知";
            String grade = student != null ? student.getGradeCode() : "";

            return new HighRiskStudentVO(studentId, name, grade, maxRisk,
                    events.size(), latestEvent);
        })
        .sorted(Comparator.comparingInt(HighRiskStudentVO::maxRiskLevel).reversed())
        .toList();
    }

    // ===== 备注管理 =====

    public TeacherNote addNote(UUID tenantId, UUID studentUserId, UUID teacherUserId,
                               String content, String noteType) {
        TeacherNote note = TeacherNote.create(tenantId, studentUserId, teacherUserId, content, noteType);
        teacherNoteMapper.insert(note);
        log.info("教师备注已添加: noteId={}, student={}", note.getNoteId(), studentUserId);
        return note;
    }

    // ===== VO 类型 =====

    public record DashboardVO(
            long pendingAlerts,
            long todayAlerts,
            long todaySessions,
            List<DailyCount> weeklyTrend
    ) {}

    public record DailyCount(String date, long count) {}

    public record AlertVO(
            UUID alertId, UUID studentUserId, String studentName,
            String riskType, int riskLevel, String status,
            Instant detectedAt, UUID assignedUserId
    ) {}

    public record StudentProfileVO(
            UUID studentUserId, String displayName,
            String gradeCode, String classCode,
            int maxRiskLevel, int totalSessions,
            List<SessionSummaryVO> recentSessions,
            List<AlertVO> alertHistory,
            List<NoteVO> notes
    ) {}

    public record SessionSummaryVO(
            UUID sessionId, Instant startedAt, String status, int riskLevel
    ) {}

    public record NoteVO(
            UUID noteId, UUID teacherUserId, String content,
            String noteType, Instant createdAt
    ) {}

    public record HighRiskStudentVO(
            UUID studentUserId, String displayName, String gradeCode,
            int maxRiskLevel, int openAlertCount, Instant lastAlertAt
    ) {}
}
