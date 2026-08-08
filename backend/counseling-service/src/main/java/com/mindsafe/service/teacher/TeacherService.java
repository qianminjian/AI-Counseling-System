package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mindsafe.domain.entity.*;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.casemanage.CaseLifecycleService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.session.SessionAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final FieldEncryptionService fieldEncryptionService;
    /** T4 批次A：会话归属校验单点（租户条件强制内置，防跨租户越权） */
    private final SessionAccessService sessionAccessService;
    /** T4 批次B：接管审计留痕随状态更新下沉（事务内） */
    private final AuditLogService auditLogService;

    // 预警待办静音规则（design/35 §4.2 降噪第 3 条，纯规则内联实例化）
    private final AlertTodoMutePolicy alertTodoMutePolicy = new AlertTodoMutePolicy();

    /** 个案跟踪标志的备注类型与生效值（复用 teacher_notes 免 schema 变更） */
    private static final String CASE_TRACKING_NOTE_TYPE = "case_tracking";
    private static final String CASE_TRACKING_ACTIVE = "active";

    /** 个案阶段推进的备注类型（复用 teacher_notes 免 schema 变更，content=阶段名） */
    private static final String CASE_STAGE_NOTE_TYPE = "case_stage";

    // 个案生命周期纯函数（无状态，同 AlertTodoMutePolicy 内联实例化先例）
    private final CaseLifecycleService caseLifecycleService = new CaseLifecycleService();

    public TeacherService(RiskEventMapper riskEventMapper,
                          CounselingSessionMapper sessionMapper,
                          UserMapper userMapper,
                          TeacherNoteMapper teacherNoteMapper,
                          NotificationMapper notificationMapper,
                          MessageSummaryMapper messageSummaryMapper,
                          FieldEncryptionService fieldEncryptionService,
                          SessionAccessService sessionAccessService,
                          AuditLogService auditLogService) {
        this.riskEventMapper = riskEventMapper;
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.teacherNoteMapper = teacherNoteMapper;
        this.notificationMapper = notificationMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.fieldEncryptionService = fieldEncryptionService;
        this.sessionAccessService = sessionAccessService;
        this.auditLogService = auditLogService;
    }

    // ===== 数据范围解析（RBAC） =====

    /**
     * 解析当前用户的数据可见范围。
     * 返回 null 表示全校可见，返回 classCode 表示仅该班级可见，
     * 返回空串 "" 表示班主任未绑定班级（无可见范围，查询自然为空结果）。
     */
    public String resolveClassScope(UUID tenantId, UUID userId, String userType) {
        if (User.USER_TYPE_CLASS_TEACHER.equals(userType)) {
            User teacher = userMapper.selectById(userId);
            if (teacher != null && teacher.getClassCode() != null && !teacher.getClassCode().isBlank()) {
                return teacher.getClassCode();
            }
            // P1 审计修复：班主任未绑定班级 → 返回空范围，不再全校可见兜底（防数据越权）
            return "";
        }
        return null; // admin / psych_teacher / teacher → 全校
    }

    // ===== 教师接管升级会话（T4 批次A/B：归属校验 + 状态更新 + 审计下沉，Controller 不再直查 Mapper） =====

    /**
     * 教师接管升级会话（红色风险转人工）：
     * 租户归属校验（SessionAccessService 强制）→ 状态更新 → 审计留痕（同一事务）。
     */
    @Transactional
    public TakeoverResult takeoverSession(UUID tenantId, UUID userId, UUID sessionId) {
        CounselingSession session = sessionAccessService.getTenantSession(tenantId, sessionId);
        if (session == null) {
            return new TakeoverResult(false, "session_not_found");
        }
        CounselingSession update = new CounselingSession();
        update.setSessionId(sessionId);
        update.setSessionStatus("taken_over");
        update.setUpdatedAt(Instant.now());
        sessionMapper.updateById(update);
        auditLogService.log(tenantId, userId, "SESSION_TAKEOVER", "session", sessionId, null);
        return new TakeoverResult(true, null);
    }

    /**
     * 接管结果（替代 Map 魔法键）。
     * 注意：success() 为 record 自动生成的存取器（返回 boolean），不可自定义同名静态工厂。
     */
    public record TakeoverResult(boolean success, String reason) {
    }

    // ===== T4 批次C：管理/教师端查询下沉（Controller 不再直查 Mapper，租户条件强制内置） =====

    /** 查询同租户学生（null 表示不存在/非本租户） */
    public User findStudentInTenant(UUID tenantId, UUID studentId) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserId, studentId)
                        .eq(User::getTenantId, tenantId)
        );
    }

    /**
     * 学生列表（同租户 + 学生 + 启用；classScope 非 null 时仅该班级，null 表示全校）。
     * getStudents 与 exportStudents 共用（DRY）。
     */
    public List<User> listActiveStudents(UUID tenantId, String classScope) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getUserType, User.USER_TYPE_STUDENT)
                .eq(User::getStatus, User.STATUS_ACTIVE)
                .orderByAsc(User::getGradeCode)
                .orderByAsc(User::getClassCode);
        if (classScope != null) {
            wrapper.eq(User::getClassCode, classScope);
        }
        return userMapper.selectList(wrapper);
    }

    /** 风险事件列表（同租户，最近 limit 条；AUD-043 分页插件安全化） */
    public List<RiskEvent> pageRiskEvents(UUID tenantId, int limit) {
        Page<RiskEvent> pageResult = riskEventMapper.selectPage(
                new Page<>(1, Math.min(limit, 100), false),
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .orderByDesc(RiskEvent::getDetectedAt)
        );
        return pageResult.getRecords();
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
                        .eq(RiskEvent::getStatus, RiskEvent.STATUS_OPEN)
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

        // 今日活跃学生数（今日有会话的去重学生）：单次 DISTINCT 查询，避免全量 会话查列表（P1-FE-2）
        List<Object> activeStudentIds = sessionMapper.selectObjs(
                new QueryWrapper<CounselingSession>()
                        .select("DISTINCT student_user_id")
                        .eq("tenant_id", tenantId)
                        .ge("started_at", todayStart));
        long activeStudents = activeStudentIds.stream().filter(Objects::nonNull).count();
        
        // 累计会话数（该租户全部会话，P1-FE-2 大屏"累计会话"卡片）
        long totalSessions = sessionMapper.selectCount(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
        );
        
        // 周趋势（最近 7 天每天的风险事件数）：单次查询 + 内存分桶，替代 7 次循环 count
        Instant weekStart = now.minus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
        Instant tomorrowStart = now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
        List<RiskEvent> weekEvents = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .ge(RiskEvent::getDetectedAt, weekStart)
                        .lt(RiskEvent::getDetectedAt, tomorrowStart)
        );
        Map<Instant, Long> eventsByDay = weekEvents.stream()
                .map(RiskEvent::getDetectedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t.truncatedTo(ChronoUnit.DAYS), Collectors.counting()));
        List<DailyCount> weeklyTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            Instant dayStart = now.minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            weeklyTrend.add(new DailyCount(dayStart.toString().substring(0, 10),
                    eventsByDay.getOrDefault(dayStart, 0L)));
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

        return new DashboardVO(pendingAlerts, todayAlerts, todaySessions, activeStudents, totalSessions,
                weeklyTrend, Math.round(avgSatisfaction * 10) / 10.0, satisfactionCount);
    }

    // ===== 预警队列 =====

    public List<AlertVO> getAlerts(UUID tenantId, String status, Integer minLevel, int limit) {
        return getAlerts(tenantId, null, status, minLevel, limit);
    }

    /**
     * 预警队列（支持班级范围过滤，P1 审计修复：班主任导出/列表不再全校可见）。
     *
     * @param classScope 班级范围（null=全校；空串=无可见范围，返回空列表）
     */
    public List<AlertVO> getAlerts(UUID tenantId, String classScope, String status, Integer minLevel, int limit) {
        // 班级范围解析：先确认该班存在学生，空班/未绑定班级直接返回空列表（B5：查询下沉 SessionAccessService）
        Set<UUID> classStudentIds = null;
        if (classScope != null) {
            List<User> classStudents = sessionAccessService.listClassStudents(tenantId, classScope);
            if (classStudents.isEmpty()) {
                return List.of();
            }
            classStudentIds = classStudents.stream().map(User::getUserId).collect(Collectors.toSet());
        }

        LambdaQueryWrapper<RiskEvent> wrapper = new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getTenantId, tenantId);

        if (status != null && !status.isBlank()) {
            wrapper.eq(RiskEvent::getStatus, status);
        }
        if (minLevel != null) {
            wrapper.ge(RiskEvent::getRiskLevel, minLevel);
        }
        wrapper.orderByDesc(RiskEvent::getRiskLevel)
                .orderByDesc(RiskEvent::getDetectedAt);

        // AUD-043：分页插件安全化，替代 .last("LIMIT ...") 字符串拼接
        Page<RiskEvent> pageResult = riskEventMapper.selectPage(new Page<>(1, Math.min(limit, 100), false), wrapper);
        List<RiskEvent> events = pageResult.getRecords();
        // 班级过滤（内存过滤，与 getHighRiskStudents 同模式，避免 inSql 注入面）
        if (classStudentIds != null) {
            Set<UUID> finalIds = classStudentIds;
            events = events.stream()
                    .filter(e -> finalIds.contains(e.getStudentUserId()))
                    .toList();
        }
        Set<UUID> caseTrackedStudents = getCaseTrackedStudentIds(tenantId);

        // 批量查询学生信息（避免 N+1）
        Set<UUID> studentIds = events.stream().map(RiskEvent::getStudentUserId).collect(Collectors.toSet());
        Map<UUID, User> studentMap = studentIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(studentIds).stream()
                        .collect(Collectors.toMap(User::getUserId, u -> u));

        return events.stream().map(e -> {
            User student = studentMap.get(e.getStudentUserId());
            String studentName = student != null ? student.getPseudonym() : "未知学生";
            boolean mutedFromTodo = alertTodoMutePolicy.isMutedFromTodo(
                    e.getRiskLevel(), caseTrackedStudents.contains(e.getStudentUserId()));
            return new AlertVO(
                    e.getRiskEventId(), e.getStudentUserId(), studentName,
                    e.getRiskType(), e.getRiskLevel(), e.getStatus(),
                    e.getDetectedAt(), e.getAssignedUserId(), mutedFromTodo
            );
        }).toList();
    }

    /**
     * 转派预警（design/35 §4.1）。
     * 规则：转派后重置为 open（目标教师的"新预警"）；重置认领但不重置 SLA（detectedAt 不变）。
     */
    @Transactional
    public void transferAlert(UUID tenantId, UUID riskEventId, UUID fromTeacherId,
                              UUID targetTeacherId, String note) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);

        // 目标教师必须存在且同租户（防止跨租户转派泄露学生数据）
        User target = userMapper.selectById(targetTeacherId);
        if (target == null || !tenantId.equals(target.getTenantId())) {
            throw new IllegalArgumentException("目标教师不存在: " + targetTeacherId);
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
            teacherNoteMapper.insert(transferNote);
        }
        log.info("预警已转派: riskEventId={}, from={}, target={}", riskEventId, fromTeacherId, targetTeacherId);
    }

    /**
     * 设置学生“已在个案跟踪中”标志（design/35 §4.2 降噪第 3 条）。
     * 落地为 teacher_notes（type=case_tracking）最新一条，避免 schema 变更。
     */
    public void setCaseTracking(UUID tenantId, UUID studentUserId, UUID teacherUserId, boolean enabled) {
        User student = userMapper.selectById(studentUserId);
        if (student == null || !tenantId.equals(student.getTenantId())) {
            throw new IllegalArgumentException("学生不存在: " + studentUserId);
        }
        TeacherNote note = TeacherNote.create(
                tenantId, studentUserId, teacherUserId,
                fieldEncryptionService.encrypt(enabled ? CASE_TRACKING_ACTIVE : "inactive"), CASE_TRACKING_NOTE_TYPE
        );
        teacherNoteMapper.insert(note);
        log.info("个案跟踪标志更新: student={}, enabled={}", studentUserId, enabled);
    }

    /** 学生是否在个案跟踪中（最新一条 case_tracking 备注为 active） */
    public boolean isCaseTracking(UUID tenantId, UUID studentUserId) {
        // AUD-043：分页插件安全化，替代 .last("LIMIT 1") 字符串拼接
        List<TeacherNote> notes = teacherNoteMapper.selectPage(
                new Page<>(1, 1, false),
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .eq(TeacherNote::getNoteType, CASE_TRACKING_NOTE_TYPE)
                        .orderByDesc(TeacherNote::getCreatedAt)
        ).getRecords();
        return !notes.isEmpty() && CASE_TRACKING_ACTIVE.equals(fieldEncryptionService.decrypt(notes.get(0).getContent()));
    }

    /** 租户内全部个案跟踪中的学生 ID 集合（批量查询避免 N+1） */
    private Set<UUID> getCaseTrackedStudentIds(UUID tenantId) {
        List<TeacherNote> notes = teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getNoteType, CASE_TRACKING_NOTE_TYPE)
        );
        // 按学生取最新一条，仅保留 active
        Map<UUID, TeacherNote> latestByStudent = new HashMap<>();
        for (TeacherNote note : notes) {
            TeacherNote current = latestByStudent.get(note.getStudentUserId());
            if (current == null || (note.getCreatedAt() != null && current.getCreatedAt() != null
                    && note.getCreatedAt().isAfter(current.getCreatedAt()))) {
                latestByStudent.put(note.getStudentUserId(), note);
            }
        }
        return latestByStudent.values().stream()
                .filter(n -> CASE_TRACKING_ACTIVE.equals(fieldEncryptionService.decrypt(n.getContent())))
                .map(TeacherNote::getStudentUserId)
                .collect(Collectors.toSet());
    }

    /** 认领预警 */
    public void claimAlert(UUID tenantId, UUID riskEventId, UUID teacherUserId) {
        RiskEvent event = getEventWithTenantCheck(tenantId, riskEventId);
        event.setStatus(RiskEvent.STATUS_CLAIMED);
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

    // ===== 个案阶段推进（P1 审计修复：transitionCase 伪 API 无持久化 → 落地 teacher_notes） =====

    /**
     * 读取学生当前个案阶段：teacher_notes（note_type=case_stage）最新一条，无记录 → INTAKE。
     */
    public CaseLifecycleService.CaseStage getCurrentCaseStage(UUID tenantId, UUID studentUserId) {
        List<TeacherNote> notes = teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .eq(TeacherNote::getNoteType, CASE_STAGE_NOTE_TYPE)
                        .orderByDesc(TeacherNote::getCreatedAt)
        );
        if (notes.isEmpty()) {
            return CaseLifecycleService.CaseStage.INTAKE;
        }
        // 内存取 createdAt 最新一条（不依赖 DB 排序语义，与 getCaseTrackedStudentIds 同模式）
        TeacherNote latest = notes.get(0);
        for (TeacherNote n : notes) {
            if (n.getCreatedAt() != null && (latest.getCreatedAt() == null
                    || n.getCreatedAt().isAfter(latest.getCreatedAt()))) {
                latest = n;
            }
        }
        try {
            return CaseLifecycleService.CaseStage.valueOf(fieldEncryptionService.decrypt(latest.getContent()));
        } catch (IllegalArgumentException e) {
            // 存量脏数据兜底：无法解析的阶段名按建案处理
            log.warn("个案阶段备注内容非法，按 INTAKE 处理: student={}, content={}",
                    studentUserId, latest.getContent());
            return CaseLifecycleService.CaseStage.INTAKE;
        }
    }

    /**
     * 推进个案阶段（学生须存在且同租户）。
     * <p>
     * 起点从存储读取（无历史=INTAKE），不可跳级；允许推进时落 case_stage 备注（content=目标阶段名，加密）。
     *
     * @return 推进结果（allowed=false 时未做任何持久化）
     */
    public CaseLifecycleService.StageTransition transitionCaseStage(
            UUID tenantId, UUID studentUserId, UUID teacherUserId,
            CaseLifecycleService.CaseStage targetStage) {
        User student = userMapper.selectById(studentUserId);
        if (student == null || !tenantId.equals(student.getTenantId())) {
            throw new IllegalArgumentException("学生不存在: " + studentUserId);
        }

        CaseLifecycleService.CaseStage current = getCurrentCaseStage(tenantId, studentUserId);
        CaseLifecycleService.StageTransition result =
                caseLifecycleService.transition(current, targetStage, false);

        if (result.allowed()) {
            TeacherNote note = TeacherNote.create(
                    tenantId, studentUserId, teacherUserId,
                    fieldEncryptionService.encrypt(targetStage.name()), CASE_STAGE_NOTE_TYPE
            );
            teacherNoteMapper.insert(note);
            log.info("个案阶段推进: student={}, {} -> {}", studentUserId, current, targetStage);
        }
        return result;
    }

    // ===== 学生档案 =====

    public StudentProfileVO getStudentProfile(UUID tenantId, UUID studentUserId, String userType) {
        User student = userMapper.selectById(studentUserId);
        if (student == null || !student.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("学生不存在: " + studentUserId);
        }

        // 班主任（class_teacher）只见沟通建议，不见风险轨迹与对话摘要（design/35 §3.3/§六：服务端裁剪）
        boolean fullAccess = !User.USER_TYPE_CLASS_TEACHER.equals(userType);

        // 教师备注（所有角色可见，沟通建议来源）
        List<TeacherNote> notes = teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .orderByDesc(TeacherNote::getCreatedAt)
        );

        // 班主任裁剪：不查询也不返回会话/预警/风险等级
        if (!fullAccess) {
            return new StudentProfileVO(
                    student.getUserId(), student.getPseudonym(),
                    student.getGradeCode(), student.getClassCode(),
                    null, 0,
                    null, null,
                    notes.stream().map(n -> new NoteVO(
                            n.getNoteId(), n.getTeacherUserId(), fieldEncryptionService.decrypt(n.getContent()),
                            n.getNoteType(), n.getCreatedAt()
                    )).toList()
            );
        }

        // 最近会话（AUD-043：分页插件安全化，替代 .last("LIMIT 10") 拼接）
        List<CounselingSession> recentSessions = sessionMapper.selectPage(
                new Page<>(1, 10, false),
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .orderByDesc(CounselingSession::getStartedAt)
        ).getRecords();

        // 预警历史（AUD-043：分页插件安全化，替代 .last("LIMIT 20") 拼接）
        List<RiskEvent> riskHistory = riskEventMapper.selectPage(
                new Page<>(1, 20, false),
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .eq(RiskEvent::getStudentUserId, studentUserId)
                        .orderByDesc(RiskEvent::getDetectedAt)
        ).getRecords();

        // 最高风险等级
        int maxRisk = riskHistory.stream()
                .mapToInt(RiskEvent::getRiskLevel)
                .max().orElse(0);

        // 个案跟踪状态（提前计算避免 stream 内重复查询）
        boolean caseTracking = isCaseTracking(tenantId, studentUserId);

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
                        e.getDetectedAt(), e.getAssignedUserId(),
                        alertTodoMutePolicy.isMutedFromTodo(e.getRiskLevel(), caseTracking)
                )).toList(),
                notes.stream().map(n -> new NoteVO(
                        n.getNoteId(), n.getTeacherUserId(), fieldEncryptionService.decrypt(n.getContent()),
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
                                                .in(RiskEvent::getStatus, RiskEvent.STATUS_OPEN, RiskEvent.STATUS_CLAIMED)
                        .orderByDesc(RiskEvent::getRiskLevel)
        );

        // 班主任班级过滤：只保留本班学生的事件（B5：查询下沉 SessionAccessService）
        Set<UUID> classStudentIds = null;
        if (classScope != null) {
            List<User> classStudents = sessionAccessService.listClassStudents(tenantId, classScope);
            classStudentIds = classStudents.stream().map(User::getUserId).collect(Collectors.toSet());
            Set<UUID> finalIds = classStudentIds;
            openEvents = openEvents.stream().filter(e -> finalIds.contains(e.getStudentUserId())).toList();
        }

        // 按学生分组
        Map<UUID, List<RiskEvent>> byStudent = openEvents.stream()
                .collect(Collectors.groupingBy(RiskEvent::getStudentUserId));

        // 批量查询学生信息（避免 N+1）
        Map<UUID, User> studentMap = byStudent.keySet().isEmpty() ? Map.of()
                : userMapper.selectBatchIds(byStudent.keySet()).stream()
                        .collect(Collectors.toMap(User::getUserId, u -> u));

        return byStudent.entrySet().stream().map(entry -> {
            UUID studentId = entry.getKey();
            List<RiskEvent> events = entry.getValue();
            int maxRisk = events.stream().mapToInt(RiskEvent::getRiskLevel).max().orElse(0);
            Instant latestEvent = events.stream()
                    .map(RiskEvent::getDetectedAt)
                    .max(Instant::compareTo).orElse(Instant.now());

            User student = studentMap.get(studentId);
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
        TeacherNote note = TeacherNote.create(tenantId, studentUserId, teacherUserId,
                fieldEncryptionService.encrypt(content), noteType);
        teacherNoteMapper.insert(note);
        note.setContent(content); // API 响应返回明文
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
                // R-01：contentSummary 字段级加密，教师端读取时解密（明文兼容透传）
                fieldEncryptionService.decrypt(m.getContentSummary()), m.getEmotionLabel(),
                m.getRiskLevel() != null ? m.getRiskLevel() : 0,
                m.getCreatedAt()
        )).toList();
    }

    // ===== 数据看板统计 =====

    public StatsVO getStats(UUID tenantId, String classScope) {
        Instant now = Instant.now();

        // 班级范围过滤：获取本班学生 ID 集合（B5：查询下沉 SessionAccessService）
        Set<UUID> scopeStudentIds = null;
        if (classScope != null) {
            List<User> classStudents = sessionAccessService.listClassStudents(tenantId, classScope);
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

        // 2. 班级对比（按 classCode 分组统计预警数 + 学生数）——复用 listActiveStudents（B5：五处范围查询收敛）
        List<User> students = listActiveStudents(tenantId, classScope);
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

        // 3. 近 30 天会话趋势：单次查询 + 内存分桶（替代 30 次循环 selectCount）
        Instant trendStart = now.minus(29, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
        Instant tomorrowStart = now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
        List<CounselingSession> trendSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, trendStart)
                        .lt(CounselingSession::getStartedAt, tomorrowStart)
        );
        Map<Instant, Long> sessionsByDay = trendSessions.stream()
                .map(CounselingSession::getStartedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t.truncatedTo(ChronoUnit.DAYS), Collectors.counting()));
        List<DailyCount> sessionTrend = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            Instant dayStart = now.minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            sessionTrend.add(new DailyCount(dayStart.toString().substring(0, 10),
                    sessionsByDay.getOrDefault(dayStart, 0L)));
        }

        // 4. 情绪分布（近 30 天 student 消息的 emotion_label）：DB GROUP BY 聚合，
        //    不再将全租户 30 天 message_summaries 加载进内存
        Instant monthAgo = now.minus(30, ChronoUnit.DAYS);
        List<Map<String, Object>> emotionRows = messageSummaryMapper.selectMaps(
                new QueryWrapper<MessageSummary>()
                        .select("emotion_label, COUNT(*) AS cnt")
                        .eq("tenant_id", tenantId)
                        .eq("sender_type", User.USER_TYPE_STUDENT)
                        .ge("created_at", monthAgo)
                        .isNotNull("emotion_label")
                        .groupBy("emotion_label")
        );
        List<EmotionItem> emotionDistribution = emotionRows.stream()
                .filter(r -> r.get("emotion_label") != null && r.get("cnt") instanceof Number)
                .map(r -> new EmotionItem((String) r.get("emotion_label"),
                        ((Number) r.get("cnt")).longValue()))
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
            long activeStudents,
            long totalSessions,
            List<DailyCount> weeklyTrend,
            double avgSatisfaction,
            long satisfactionCount
    ) {}

    public record DailyCount(String date, long count) {}

    public record AlertVO(
            UUID alertId, UUID studentUserId, String studentName,
            String riskType, int riskLevel, String status,
            Instant detectedAt, UUID assignedUserId,
            boolean mutedFromTodo
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentProfileVO(
            UUID studentUserId, String displayName,
            String gradeCode, String classCode,
            Integer maxRiskLevel, int totalSessions,
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
        // DB 端聚合：全量 + 近 7 天各一次 GROUP BY，
        // 不再将全量历史已评会话加载进内存（审计 fix-perf）
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Map<Integer, Long> dist = ratingDistribution(tenantId, null);
        Map<Integer, Long> recentDist = ratingDistribution(tenantId, weekAgo);

        long total = dist.values().stream().mapToLong(Long::longValue).sum();
        long weightedSum = dist.entrySet().stream()
                .mapToLong(e -> (long) e.getKey() * e.getValue()).sum();
        double avg = total == 0 ? 0.0 : weightedSum / (double) total;

        List<RatingDistItem> distribution = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            distribution.add(new RatingDistItem(i, dist.getOrDefault(i, 0L)));
        }

        long recentCount = recentDist.values().stream().mapToLong(Long::longValue).sum();
        long recentSum = recentDist.entrySet().stream()
                .mapToLong(e -> (long) e.getKey() * e.getValue()).sum();
        double recentAvg = recentCount == 0 ? 0.0 : recentSum / (double) recentCount;

        return new SatisfactionStatsVO(total, Math.round(avg * 10) / 10.0, distribution,
                recentCount, Math.round(recentAvg * 10) / 10.0);
    }

    /** 评分分布聚合（rating → count）；since 非空时仅统计该时点之后的会话 */
    private Map<Integer, Long> ratingDistribution(UUID tenantId, Instant since) {
        QueryWrapper<CounselingSession> wrapper = new QueryWrapper<CounselingSession>()
                .select("satisfaction_rating AS rating, COUNT(*) AS cnt")
                .eq("tenant_id", tenantId)
                .isNotNull("satisfaction_rating")
                .groupBy("satisfaction_rating");
        if (since != null) {
            wrapper.ge("started_at", since);
        }
        Map<Integer, Long> result = new HashMap<>();
        for (Map<String, Object> row : sessionMapper.selectMaps(wrapper)) {
            if (row.get("rating") instanceof Number rating && row.get("cnt") instanceof Number cnt) {
                result.put(rating.intValue(), cnt.longValue());
            }
        }
        return result;
    }

    public record SatisfactionStatsVO(long totalRated, double avgRating, List<RatingDistItem> distribution,
                                      long recentCount, double recentAvg) {}
    public record RatingDistItem(int stars, long count) {}
}
