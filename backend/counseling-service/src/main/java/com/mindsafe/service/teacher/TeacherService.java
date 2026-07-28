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
 * 功能：工作台概览 / 预警队列 / 认领&误报&处理 / 学生档案 / 备注管理 / 对话摘要查看
 */
@Service
public class TeacherService {

    private static final Logger log = LoggerFactory.getLogger(TeacherService.class);

    private final RiskEventMapper riskEventMapper;
    private final CounselingSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final TeacherNoteMapper teacherNoteMapper;
    private final NotificationMapper notificationMapper;
    private final MessageSummaryMapper messageSummaryMapper;

    public TeacherService(RiskEventMapper riskEventMapper,
                          CounselingSessionMapper sessionMapper,
                          UserMapper userMapper,
                          TeacherNoteMapper teacherNoteMapper,
                          NotificationMapper notificationMapper,
                          MessageSummaryMapper messageSummaryMapper) {
        this.riskEventMapper = riskEventMapper;
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.teacherNoteMapper = teacherNoteMapper;
        this.notificationMapper = notificationMapper;
        this.messageSummaryMapper = messageSummaryMapper;
    }

    // ===== 数据范围解析（RBAC） =====

    /**
     * 解析当前用户的数据可见范围。
     * 返回 null 表示全校可见，返回 classCode 表示仅该班级可见。
     */
    public String resolveClassScope(UUID tenantId, UUID userId, String userType) {
        if ("class_teacher".equals(userType)) {
            User teacher = userMapper.selectById(userId);
            if (teacher != null && teacher.getClassCode() != null && !teacher.getClassCode().isBlank()) {
                return teacher.getClassCode();
            }
            // 班主任未绑定班级 → 默认全校可见（避免空数据）
        }
        return null; // admin / psych_teacher / teacher → 全校
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

        // 满意度统计（近 30 天有评价的会话）
        Instant monthAgo = now.minus(30, ChronoUnit.DAYS);
        List<CounselingSession> ratedSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, monthAgo)
                        .isNotNull(CounselingSession::getSatisfactionRating)
        );
        double avgSatisfaction = ratedSessions.stream()
                .mapToInt(CounselingSession::getSatisfactionRating)
                .average().orElse(0.0);
        long satisfactionCount = ratedSessions.size();

        return new DashboardVO(pendingAlerts, todayAlerts, todaySessions, weeklyTrend,
                Math.round(avgSatisfaction * 10) / 10.0, satisfactionCount);
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
    public void claimAlert(UUID tenantId, UUID riskEventId, UUID teacherUserId) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus("claimed");
        event.setAssignedUserId(teacherUserId);
        event.setUpdatedAt(Instant.now());
        riskEventMapper.updateById(event);
        log.info("预警已认领: riskEventId={}, teacher={}", riskEventId, teacherUserId);
    }

    /** 标记误报 */
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
    public void resolveAlert(UUID tenantId, UUID riskEventId, UUID teacherUserId, String resolutionNote) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus("resolved");
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
            teacherNoteMapper.insert(note);
        }

        log.info("预警已处理: riskEventId={}, teacher={}", riskEventId, teacherUserId);
    }

    /** DATA-004：安排回访（处置后不直接关闭，而是计划回访确认效果） */
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
    public void completeFollowUp(UUID tenantId, UUID riskEventId, UUID teacherUserId,
                                 String followUpNote, String outcome) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus("closed");
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
            teacherNoteMapper.insert(note);
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
            throw new IllegalArgumentException("预警不存在: " + riskEventId);
        }
        return event;
    }

    // ===== 学生档案 =====

    public StudentProfileVO getStudentProfile(UUID tenantId, UUID studentUserId) {
        User student = userMapper.selectById(studentUserId);
        if (student == null || !student.getTenantId().equals(tenantId)) {
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
                        s.getRiskLevelSnapshot(), s.getSatisfactionRating()
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
    public List<HighRiskStudentVO> getHighRiskStudents(UUID tenantId, String classScope) {
        // 查询所有 open/claimed 状态的风险事件，按学生分组取最高风险
        List<RiskEvent> openEvents = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .in(RiskEvent::getStatus, "open", "claimed")
                        .orderByDesc(RiskEvent::getRiskLevel)
        );

        // 班主任班级过滤：只保留本班学生的事件
        Set<UUID> classStudentIds = null;
        if (classScope != null) {
            List<User> classStudents = userMapper.selectList(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getTenantId, tenantId)
                            .eq(User::getUserType, "student")
                            .eq(User::getClassCode, classScope)
            );
            classStudentIds = classStudents.stream().map(User::getUserId).collect(Collectors.toSet());
            Set<UUID> finalIds = classStudentIds;
            openEvents = openEvents.stream().filter(e -> finalIds.contains(e.getStudentUserId())).toList();
        }

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

    // ===== 对话摘要查看 =====

    /** 查看某次会话的消息摘要列表 */
    public List<MessageSummaryVO> getSessionMessages(UUID tenantId, UUID sessionId) {
        List<MessageSummary> summaries = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getSessionId, sessionId)
                        .orderByAsc(MessageSummary::getTurnCount)
                        .orderByAsc(MessageSummary::getCreatedAt)
        );
        return summaries.stream().map(m -> new MessageSummaryVO(
                m.getSummaryId(), m.getSenderType(), m.getTurnCount(),
                m.getContentSummary(), m.getEmotionLabel(),
                m.getRiskLevel() != null ? m.getRiskLevel() : 0,
                m.getCreatedAt()
        )).toList();
    }

    // ===== 数据看板统计 =====

    public StatsVO getStats(UUID tenantId, String classScope) {
        Instant now = Instant.now();

        // 班级范围过滤：获取本班学生 ID 集合
        Set<UUID> scopeStudentIds = null;
        if (classScope != null) {
            List<User> classStudents = userMapper.selectList(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getTenantId, tenantId)
                            .eq(User::getUserType, "student")
                            .eq(User::getClassCode, classScope)
            );
            scopeStudentIds = classStudents.stream().map(User::getUserId).collect(Collectors.toSet());
        }

        // 1. 风险等级分布
        LambdaQueryWrapper<RiskEvent> eventWrapper = new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getTenantId, tenantId);
        if (scopeStudentIds != null && !scopeStudentIds.isEmpty()) {
            eventWrapper.in(RiskEvent::getStudentUserId, scopeStudentIds);
        } else if (scopeStudentIds != null) {
            return new StatsVO(List.of(), List.of(), List.of(), List.of()); // 空班级
        }
        List<RiskEvent> allEvents = riskEventMapper.selectList(eventWrapper);
        Map<Integer, Long> riskByLevel = allEvents.stream()
                .collect(Collectors.groupingBy(RiskEvent::getRiskLevel, Collectors.counting()));
        List<RiskDistItem> riskDistribution = List.of(
                new RiskDistItem(1, "黄色", riskByLevel.getOrDefault(1, 0L)),
                new RiskDistItem(2, "橙色", riskByLevel.getOrDefault(2, 0L)),
                new RiskDistItem(3, "红色", riskByLevel.getOrDefault(3, 0L))
        );

        // 2. 班级对比（按 classCode 分组统计预警数 + 学生数）
        LambdaQueryWrapper<User> studentWrapper = new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getUserType, "student")
                .eq(User::getStatus, "active");
        if (classScope != null) {
            studentWrapper.eq(User::getClassCode, classScope);
        }
        List<User> students = userMapper.selectList(studentWrapper);
        Map<String, Long> studentByClass = students.stream()
                .filter(s -> s.getClassCode() != null && !s.getClassCode().isBlank())
                .collect(Collectors.groupingBy(User::getClassCode, Collectors.counting()));

        // 按学生 userId 关联预警
        Map<UUID, String> studentClass = students.stream()
                .filter(s -> s.getClassCode() != null)
                .collect(Collectors.toMap(User::getUserId, User::getClassCode, (a, b) -> a));
        Map<String, Long> alertByClass = allEvents.stream()
                .map(e -> studentClass.get(e.getStudentUserId()))
                .filter(c -> c != null)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

        List<ClassRiskItem> classComparison = studentByClass.entrySet().stream()
                .map(e -> new ClassRiskItem(e.getKey(),
                        alertByClass.getOrDefault(e.getKey(), 0L), e.getValue()))
                .sorted(Comparator.comparingLong(ClassRiskItem::alertCount).reversed())
                .limit(10)
                .toList();

        // 3. 近 30 天会话趋势
        List<DailyCount> sessionTrend = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            Instant dayStart = now.minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            long count = sessionMapper.selectCount(
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getTenantId, tenantId)
                            .ge(CounselingSession::getStartedAt, dayStart)
                            .lt(CounselingSession::getStartedAt, dayEnd)
            );
            sessionTrend.add(new DailyCount(dayStart.toString().substring(0, 10), count));
        }

        // 4. 情绪分布（近 30 天 message_summaries 中 student 消息的 emotion_label）
        Instant monthAgo = now.minus(30, ChronoUnit.DAYS);
        List<MessageSummary> emotions = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getSenderType, "student")
                        .ge(MessageSummary::getCreatedAt, monthAgo)
                        .isNotNull(MessageSummary::getEmotionLabel)
        );
        Map<String, Long> emotionMap = emotions.stream()
                .collect(Collectors.groupingBy(MessageSummary::getEmotionLabel, Collectors.counting()));
        List<EmotionItem> emotionDistribution = emotionMap.entrySet().stream()
                .map(e -> new EmotionItem(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(EmotionItem::count).reversed())
                .limit(8)
                .toList();

        return new StatsVO(riskDistribution, classComparison, sessionTrend, emotionDistribution);
    }

    // ===== VO 类型 =====

    public record DashboardVO(
            long pendingAlerts,
            long todayAlerts,
            long todaySessions,
            List<DailyCount> weeklyTrend,
            double avgSatisfaction,
            long satisfactionCount
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
            UUID sessionId, Instant startedAt, String status, int riskLevel,
            Integer satisfactionRating
    ) {}

    public record NoteVO(
            UUID noteId, UUID teacherUserId, String content,
            String noteType, Instant createdAt
    ) {}

    public record HighRiskStudentVO(
            UUID studentUserId, String displayName, String gradeCode,
            int maxRiskLevel, int openAlertCount, Instant lastAlertAt
    ) {}

    public record MessageSummaryVO(
            UUID summaryId, String senderType, int turnCount,
            String contentSummary, String emotionLabel,
            int riskLevel, Instant createdAt
    ) {}

    public record StatsVO(
            List<RiskDistItem> riskDistribution,
            List<ClassRiskItem> classComparison,
            List<DailyCount> sessionTrend,
            List<EmotionItem> emotionDistribution
    ) {}

    public record RiskDistItem(int level, String label, long count) {}

    public record ClassRiskItem(String classCode, long alertCount, long studentCount) {}

    public record EmotionItem(String emotion, long count) {}

    // ===== 满意度统计 =====

    public SatisfactionStatsVO getSatisfactionStats(UUID tenantId) {
        List<CounselingSession> rated = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .isNotNull(CounselingSession::getSatisfactionRating)
        );

        long total = rated.size();
        double avg = rated.stream().mapToInt(CounselingSession::getSatisfactionRating).average().orElse(0);

        Map<Integer, Long> dist = rated.stream()
                .collect(java.util.stream.Collectors.groupingBy(CounselingSession::getSatisfactionRating,
                        java.util.stream.Collectors.counting()));
        List<RatingDistItem> distribution = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            distribution.add(new RatingDistItem(i, dist.getOrDefault(i, 0L)));
        }

        java.time.Instant weekAgo = java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);
        List<CounselingSession> recent = rated.stream()
                .filter(s -> s.getStartedAt() != null && s.getStartedAt().isAfter(weekAgo))
                .toList();
        double recentAvg = recent.stream().mapToInt(CounselingSession::getSatisfactionRating).average().orElse(0);

        return new SatisfactionStatsVO(total, Math.round(avg * 10) / 10.0, distribution,
                recent.size(), Math.round(recentAvg * 10) / 10.0);
    }

    public record SatisfactionStatsVO(long totalRated, double avgRating, List<RatingDistItem> distribution,
                                      long recentCount, double recentAvg) {}
    public record RatingDistItem(int stars, long count) {}
}
