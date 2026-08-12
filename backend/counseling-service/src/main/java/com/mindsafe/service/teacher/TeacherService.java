package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.*;
import com.mindsafe.domain.mapper.*;
import com.mindsafe.service.common.CounselingTimeZone;
import com.mindsafe.service.casemanage.CaseLifecycleService;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.session.SessionAccessService;
import com.mindsafe.service.conversation.MessageSummaryService;
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
    /** S-007①（doing/93）：备注读写单点 */
    private final TeacherNoteStore teacherNoteStore;
    private final NotificationMapper notificationMapper;
    private final MessageSummaryMapper messageSummaryMapper;
    /** S-006（doing/93）：转写读取单点（BA-10），教师侧读路径收敛 */
    private final MessageSummaryService messageSummaryService;
    private final FieldEncryptionService fieldEncryptionService;
    /** T4 批次A：会话归属校验单点（租户条件强制内置，防跨租户越权） */
    private final SessionAccessService sessionAccessService;
    /** T4 批次B：接管审计留痕随状态更新下沉（事务内） */
    private final AuditLogService auditLogService;

    // 预警待办静音规则（design/35 §4.2 降噪第 3 条，纯规则内联实例化）
    private final AlertTodoMutePolicy alertTodoMutePolicy;

    /** 个案跟踪标志的备注类型与生效值（复用 teacher_notes 免 schema 变更） */
    private static final String CASE_TRACKING_NOTE_TYPE = "case_tracking";
    private static final String CASE_TRACKING_ACTIVE = "active";

    /** 个案阶段推进的备注类型（复用 teacher_notes 免 schema 变更，content=阶段名） */
    private static final String CASE_STAGE_NOTE_TYPE = "case_stage";

    /** 干预话术模板（7 条，R-7：自 Controller 下沉 service 层；心理干预话术属预审核合规内容，变更走发布评审） */
    public static final List<Map<String, String>> TEMPLATES = List.of(
            Map.of("id", "t1", "category", "预警处理", "content", "已与学生进行一对一谈话，学生情绪稳定，表示只是随口说说。已告知班主任关注。"),
            Map.of("id", "t2", "category", "预警处理", "content", "已联系家长沟通，家长表示近期家庭有变动，会配合关注学生情绪变化。"),
            Map.of("id", "t3", "category", "预警处理", "content", "误报。学生是在讨论课文内容/新闻事件，非自身情绪表达。"),
            Map.of("id", "t4", "category", "个案备注", "content", "学生近期情绪低落，已安排每周一次心理辅导，持续跟踪。"),
            Map.of("id", "t5", "category", "个案备注", "content", "学生状态明显好转，主动参与课堂活动，建议降低关注等级。"),
            Map.of("id", "t6", "category", "家长沟通", "content", "建议家长多关注孩子情绪变化，保持开放沟通，避免过度施压。如持续异常请联系学校心理老师。"),
            Map.of("id", "t7", "category", "转介建议", "content", "学生情况超出学校辅导能力，建议转介至专业心理机构进一步评估。")
    );

    /** 数据看板风险统计时间窗（B-15：近 90 天，覆盖学期活动窗口，避免全量历史加载内存） */
    private static final int STATS_RISK_WINDOW_DAYS = 90;

    // N-007（2026-08-11）：CaseLifecycleService/AlertTodoMutePolicy 反哺 Spring 注入（替换内联 new，恢复替换接缝）
    private final CaseLifecycleService caseLifecycleService;
    /** S-007②（doing/93）：预警生命周期状态机 */
    private final AlertLifecycleService alertLifecycleService;
    /** S-007②（doing/93）：工作台看板统计 */
    private final TeacherDashboardService dashboardService;

    public TeacherService(RiskEventMapper riskEventMapper,
                          CounselingSessionMapper sessionMapper,
                          UserMapper userMapper,
                          TeacherNoteStore teacherNoteStore,
                          NotificationMapper notificationMapper,
                          MessageSummaryMapper messageSummaryMapper,
                          FieldEncryptionService fieldEncryptionService,
                          SessionAccessService sessionAccessService,
                          AuditLogService auditLogService,
                          AlertTodoMutePolicy alertTodoMutePolicy,
                          CaseLifecycleService caseLifecycleService,
                          MessageSummaryService messageSummaryService,
                             AlertLifecycleService alertLifecycleService,
                             TeacherDashboardService dashboardService) {
        this.riskEventMapper = riskEventMapper;
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.teacherNoteStore = teacherNoteStore;
        this.notificationMapper = notificationMapper;
        this.messageSummaryMapper = messageSummaryMapper;
        this.fieldEncryptionService = fieldEncryptionService;
        this.sessionAccessService = sessionAccessService;
        this.auditLogService = auditLogService;
        this.alertTodoMutePolicy = alertTodoMutePolicy;
        this.caseLifecycleService = caseLifecycleService;
        this.alertLifecycleService = alertLifecycleService;
        this.dashboardService = dashboardService;
        this.messageSummaryService = messageSummaryService;
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

    /**
     * BUG-UI-03：教师端学生列表可见学生（active + withdrawn 冻结等全部非删除学生）——撤回同意冻结的
     * 学生须在列表可见并带状态标识（教师端可识别），导出/统计仍走 active 过滤的 listActiveStudents。
     */
    public List<User> listVisibleStudents(UUID tenantId, String classScope) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getUserType, User.USER_TYPE_STUDENT)
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

    /** 工作台概览（S-007②：委托 TeacherDashboardService 统计子域） */
    public DashboardVO getDashboard(UUID tenantId, UUID teacherUserId) {
        return dashboardService.getDashboard(tenantId);
    }

    // ===== 预警队列 =====

    public List<AlertVO> getAlerts(UUID tenantId, String status, Integer minLevel, int limit) {
        return getAlerts(tenantId, null, status, minLevel, limit, 100);
    }

    /**
     * 预警队列（支持班级范围过滤，P1 审计修复：班主任导出/列表不再全校可见）。
     *
     * @param classScope 班级范围（null=全校；空串=无可见范围，返回空列表）
     */
    public List<AlertVO> getAlerts(UUID tenantId, String classScope, String status, Integer minLevel, int limit) {
        return getAlerts(tenantId, classScope, status, minLevel, limit, 100);
    }

    /**
     * 预警导出路径（B-01：独立上限 5000，不再被列表 100 钳制静默截断；超限时由调用方显式提示）。
     */
    public List<AlertVO> getAlertsForExport(UUID tenantId, String classScope, String status, Integer minLevel, int limit) {
        return getAlerts(tenantId, classScope, status, minLevel, limit, 5000);
    }

    private List<AlertVO> getAlerts(UUID tenantId, String classScope, String status, Integer minLevel,
                                    int limit, int hardLimit) {
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
        // B-01：班级范围下推 SQL（in 本班学生集合），不再先全校分页再内存过滤——
        // 否则本班低风险/较旧事件滑出前 100 窗口即漏报
        if (classStudentIds != null) {
            wrapper.in(RiskEvent::getStudentUserId, classStudentIds);
        }
        wrapper.orderByDesc(RiskEvent::getRiskLevel)
                .orderByDesc(RiskEvent::getDetectedAt);

        // AUD-043：分页插件安全化，替代 .last("LIMIT ...") 字符串拼接
        Page<RiskEvent> pageResult = riskEventMapper.selectPage(new Page<>(1, Math.min(limit, hardLimit), false), wrapper);
        List<RiskEvent> events = pageResult.getRecords();
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
    /** 预警转派（S-007②：委托预警生命周期状态机） */
    @Transactional
    public void transferAlert(UUID tenantId, UUID riskEventId, UUID fromTeacherId,
                              UUID targetTeacherId, String note) {
        alertLifecycleService.transferAlert(tenantId, riskEventId, fromTeacherId, targetTeacherId, note);
    }

    /**
     * 设置学生“已在个案跟踪中”标志（design/35 §4.2 降噪第 3 条）。
     * 落地为 teacher_notes（type=case_tracking）最新一条，避免 schema 变更。
     */
    @Transactional
    public void setCaseTracking(UUID tenantId, UUID studentUserId, UUID teacherUserId, boolean enabled) {
        User student = userMapper.selectById(studentUserId);
        if (student == null || !tenantId.equals(student.getTenantId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在: " + studentUserId);
        }
        TeacherNote note = TeacherNote.create(
                tenantId, studentUserId, teacherUserId,
                fieldEncryptionService.encrypt(enabled ? CASE_TRACKING_ACTIVE : "inactive"), CASE_TRACKING_NOTE_TYPE
        );
        teacherNoteStore.insert(note);
        log.info("个案跟踪标志更新: student={}, enabled={}", studentUserId, enabled);
    }

    /** 学生是否在个案跟踪中（最新一条 case_tracking 备注为 active） */
    public boolean isCaseTracking(UUID tenantId, UUID studentUserId) {
        // S-007①：备注读写单点（AUD-043 分页安全语义由 TeacherNoteStore 承担）
        return teacherNoteStore.latest(tenantId, studentUserId, CASE_TRACKING_NOTE_TYPE)
                .map(note -> CASE_TRACKING_ACTIVE.equals(fieldEncryptionService.decrypt(note.getContent())))
                .orElse(false);
    }

    /** 租户内全部个案跟踪中的学生 ID 集合（批量查询避免 N+1） */
    private Set<UUID> getCaseTrackedStudentIds(UUID tenantId) {
        // S-007①：按学生取最新一条（备注单点）
        Map<UUID, TeacherNote> latestByStudent = teacherNoteStore.latestByStudent(tenantId, CASE_TRACKING_NOTE_TYPE);
        return latestByStudent.values().stream()
                .filter(n -> CASE_TRACKING_ACTIVE.equals(fieldEncryptionService.decrypt(n.getContent())))
                .map(TeacherNote::getStudentUserId)
                .collect(Collectors.toSet());
    }

    /** 认领预警 */
    @Transactional
    /** 认领预警（S-007②：委托预警生命周期状态机） */
    public void claimAlert(UUID tenantId, UUID riskEventId, UUID teacherUserId) {
        alertLifecycleService.claimAlert(tenantId, riskEventId, teacherUserId);
    }

    /** 标记误报（S-007②：委托预警生命周期状态机） */
    public void markFalsePositive(UUID tenantId, UUID riskEventId, UUID teacherUserId) {
        alertLifecycleService.markFalsePositive(tenantId, riskEventId, teacherUserId);
    }

    /** 处理完成（S-007②：委托预警生命周期状态机） */
    public void resolveAlert(UUID tenantId, UUID riskEventId, UUID teacherUserId, String resolutionNote) {
        alertLifecycleService.resolveAlert(tenantId, riskEventId, teacherUserId, resolutionNote);
    }

    /** 安排回访（S-007②：委托预警生命周期状态机） */
    public void scheduleFollowUp(UUID tenantId, UUID riskEventId, UUID teacherUserId, String followUpAtIso) {
        alertLifecycleService.scheduleFollowUp(tenantId, riskEventId, teacherUserId, followUpAtIso);
    }

    /** 完成回访（S-007②：委托预警生命周期状态机） */
    public void completeFollowUp(UUID tenantId, UUID riskEventId, UUID teacherUserId,
                                 String followUpNote, String outcome) {
        alertLifecycleService.completeFollowUp(tenantId, riskEventId, teacherUserId, followUpNote, outcome);
    }

    /** 查询待回访事件列表（S-007②：委托预警生命周期状态机） */
    public List<RiskEvent> getPendingFollowUps(UUID tenantId) {
        return alertLifecycleService.getPendingFollowUps(tenantId);
    }

    // ===== 个案阶段推进（P1 审计修复：transitionCase 伪 API 无持久化 → 落地 teacher_notes） =====

    /**
     * 读取学生当前个案阶段：teacher_notes（note_type=case_stage）最新一条，无记录 → INTAKE。
     */
    public CaseLifecycleService.CaseStage getCurrentCaseStage(UUID tenantId, UUID studentUserId) {
        // S-007①：备注读写单点
        List<TeacherNote> notes = teacherNoteStore.list(tenantId, studentUserId, CASE_STAGE_NOTE_TYPE);
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
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在: " + studentUserId);
        }

        CaseLifecycleService.CaseStage current = getCurrentCaseStage(tenantId, studentUserId);
        CaseLifecycleService.StageTransition result =
                caseLifecycleService.transition(current, targetStage, false);

        if (result.allowed()) {
            TeacherNote note = TeacherNote.create(
                    tenantId, studentUserId, teacherUserId,
                    fieldEncryptionService.encrypt(targetStage.name()), CASE_STAGE_NOTE_TYPE
            );
            teacherNoteStore.insert(note);
            log.info("个案阶段推进: student={}, {} -> {}", studentUserId, current, targetStage);
        }
        return result;
    }

    // ===== 学生档案 =====

    public StudentProfileVO getStudentProfile(UUID tenantId, UUID studentUserId, String userType) {
        User student = userMapper.selectById(studentUserId);
        if (student == null || !student.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在: " + studentUserId);
        }

        // 班主任（class_teacher）只见沟通建议，不见风险轨迹与对话摘要（design/35 §3.3/§六：服务端裁剪）
        boolean fullAccess = !User.USER_TYPE_CLASS_TEACHER.equals(userType);

        // 教师备注（所有角色可见，沟通建议来源；S-007① 备注读写单点）
        List<TeacherNote> notes = teacherNoteStore.listAll(tenantId, studentUserId);

        // 班主任裁剪：不查询也不返回会话/预警/风险等级
        if (!fullAccess) {
            return new StudentProfileVO(
                    student.getUserId(), student.getPseudonym(),
                    student.getGradeCode(), student.getClassCode(),
                    student.getStatus(), null, 0,
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
                student.getStatus(), maxRisk, recentSessions.size(),
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

    // ===== 干预话术模板（R-7：下沉 service 层维护） =====

    /** 获取干预话术模板列表（不可变常量，调用方直接透传） */
    public List<Map<String, String>> getTemplates() {
        return TEMPLATES;
    }

    // ===== 备注管理 =====

    public TeacherNote addNote(UUID tenantId, UUID studentUserId, UUID teacherUserId,
                               String content, String noteType) {
        TeacherNote note = TeacherNote.create(tenantId, studentUserId, teacherUserId,
                fieldEncryptionService.encrypt(content), noteType);
        teacherNoteStore.insert(note);
        note.setContent(content); // API 响应返回明文
        log.info("教师备注已添加: noteId={}, student={}", note.getNoteId(), studentUserId);
        return note;
    }

    // ===== 对话摘要查看 =====

    /** 查看某次会话的消息摘要列表 */
    public List<MessageSummaryVO> getSessionMessages(UUID tenantId, UUID sessionId) {
        // S-006（doing/93）：收敛至 BA-10 转写单点（查询+解密+保密告知过滤，语义与摘要链路一致）
        List<MessageSummary> summaries = messageSummaryService.readDecryptedMessages(tenantId, sessionId);
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

        // 班级范围过滤：获取本班学生 ID 集合（B5：查询下沉 SessionAccessService）
        Set<UUID> scopeStudentIds = null;
        if (classScope != null) {
            List<User> classStudents = sessionAccessService.listClassStudents(tenantId, classScope);
            scopeStudentIds = classStudents.stream().map(User::getUserId).collect(Collectors.toSet());
        }

        // 1. 风险等级分布（B-15：近 STATS_RISK_WINDOW_DAYS 天时间窗，不再全量历史加载内存）
        LambdaQueryWrapper<RiskEvent> eventWrapper = new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getTenantId, tenantId)
                .ge(RiskEvent::getDetectedAt, now.minus(STATS_RISK_WINDOW_DAYS, ChronoUnit.DAYS));
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
        Instant trendStart = CounselingTimeZone.truncateToDay(now.minus(29, ChronoUnit.DAYS));
        Instant tomorrowStart = CounselingTimeZone.startOfNextDay(now);
        List<CounselingSession> trendSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .ge(CounselingSession::getStartedAt, trendStart)
                        .lt(CounselingSession::getStartedAt, tomorrowStart)
        );
        Map<Instant, Long> sessionsByDay = trendSessions.stream()
                .map(CounselingSession::getStartedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(CounselingTimeZone::truncateToDay, Collectors.counting()));
        List<DailyCount> sessionTrend = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            Instant dayStart = CounselingTimeZone.truncateToDay(now.minus(i, ChronoUnit.DAYS));
            sessionTrend.add(new DailyCount(CounselingTimeZone.dateKey(dayStart),
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
            String status, Integer maxRiskLevel, int totalSessions,
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
