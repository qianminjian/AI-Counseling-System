package com.mindsafe.service.teacher;

import com.mindsafe.service.conversation.MessageSummaryService;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.NotificationMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.TeacherNoteMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.security.FieldEncryptionService;
import com.mindsafe.service.audit.AuditLogService;
import com.mindsafe.service.session.SessionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeacherService 统计查询性能回归测试（审计 fix-perf）。
 * <p>
 * 防回归点：
 * 1. sessionTrend 禁止 30 次循环 selectCount（单次查询 + 内存分桶）；
 * 2. 情绪分布禁止加载全租户 30 天 message_summaries 到内存（DB GROUP BY 聚合）；
 * 3. getSatisfactionStats 禁止加载全量历史已评会话（DB 聚合查询）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("教师端统计查询性能")
class TeacherStatsPerformanceTest {

    @Mock private RiskEventMapper riskEventMapper;
    @Mock private CounselingSessionMapper sessionMapper;
    @Mock private UserMapper userMapper;
    @Mock private TeacherNoteMapper teacherNoteMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private MessageSummaryMapper messageSummaryMapper;
    @Mock private FieldEncryptionService fieldEncryptionService;
    @Mock private SessionAccessService sessionAccessService;

    private TeacherService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TeacherService(riskEventMapper, sessionMapper, userMapper,
                new TeacherNoteStore(teacherNoteMapper), notificationMapper, messageSummaryMapper, fieldEncryptionService,
                sessionAccessService, mock(AuditLogService.class),
                new com.mindsafe.service.teacher.AlertTodoMutePolicy(),
                new com.mindsafe.service.casemanage.CaseLifecycleService(), mock(MessageSummaryService.class));
        // getStats 其余部分的默认返回（本测试聚焦趋势/情绪/满意度三处）
        lenient().when(riskEventMapper.selectList(any())).thenReturn(List.of());
        lenient().when(userMapper.selectList(any())).thenReturn(List.of());
        lenient().when(sessionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(messageSummaryMapper.selectMaps(any())).thenReturn(List.of());
        // B5：班级范围查询下沉 SessionAccessService（本文件仅 classScope 测试触碰）
        lenient().when(sessionAccessService.listClassStudents(any(), any())).thenReturn(List.of());
    }

    private CounselingSession sessionAt(Instant startedAt) {
        CounselingSession s = new CounselingSession();
        s.setStartedAt(startedAt);
        return s;
    }

    @Test
    @DisplayName("getStats 会话趋势：单次查询 + 内存分桶，不再逐日 selectCount")
    void sessionTrend_singleQueryBucketed() {
        Instant now = Instant.now();
        // B-03 对齐：服务端按上海日界分桶（CounselingTimeZone.startOfDay），测试基准须一致
        // （UTC truncate 在 0-8 点窗口漂移前一天，今日会话被分到昨天桶导致断言失败）
        Instant todayStart = com.mindsafe.service.common.CounselingTimeZone.startOfDay(now);
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                sessionAt(todayStart.plus(1, ChronoUnit.MINUTES)),
                sessionAt(todayStart.plus(2, ChronoUnit.MINUTES)),
                sessionAt(com.mindsafe.service.common.CounselingTimeZone.truncateToDay(
                        now.minus(2, ChronoUnit.DAYS)).plus(6, ChronoUnit.HOURS))));

        TeacherService.StatsVO stats = service.getStats(tenantId, null);

        assertThat(stats.sessionTrend()).hasSize(30);
        // 今天两条、两天前一条，其余补零
        assertThat(stats.sessionTrend().get(29).count()).isEqualTo(2);
        assertThat(stats.sessionTrend().get(27).count()).isEqualTo(1);
        assertThat(stats.sessionTrend().get(0).count()).isZero();
        verify(sessionMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("getStats 情绪分布：DB GROUP BY 聚合，不再整表加载 message_summaries")
    void emotionDistribution_dbAggregation() {
        when(messageSummaryMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("emotion_label", "angry", "cnt", 1L),
                Map.of("emotion_label", "sad", "cnt", 3L)));

        TeacherService.StatsVO stats = service.getStats(tenantId, null);

        assertThat(stats.emotionDistribution())
                .containsExactly(
                        new TeacherService.EmotionItem("sad", 3L),
                        new TeacherService.EmotionItem("angry", 1L));
        verify(messageSummaryMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("getSatisfactionStats：DB 聚合两次查询，不再加载全量历史已评会话")
    void satisfactionStats_dbAggregation() {
        // 全量：5星×2、4星×1、3星×1 → total=4, avg=4.25→4.3
        // 近 7 天：5星×1
        when(sessionMapper.selectMaps(any())).thenReturn(
                List.of(Map.of("rating", 5, "cnt", 2L),
                        Map.of("rating", 4, "cnt", 1L),
                        Map.of("rating", 3, "cnt", 1L)),
                List.of(Map.of("rating", 5, "cnt", 1L)));

        TeacherService.SatisfactionStatsVO stats = service.getSatisfactionStats(tenantId);

        assertThat(stats.totalRated()).isEqualTo(4);
        assertThat(stats.avgRating()).isEqualTo(4.3);
        assertThat(stats.distribution()).containsExactly(
                new TeacherService.RatingDistItem(1, 0),
                new TeacherService.RatingDistItem(2, 0),
                new TeacherService.RatingDistItem(3, 1),
                new TeacherService.RatingDistItem(4, 1),
                new TeacherService.RatingDistItem(5, 2));
        assertThat(stats.recentCount()).isEqualTo(1);
        assertThat(stats.recentAvg()).isEqualTo(5.0);
        verify(sessionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("getSatisfactionStats：无评价数据 → 全零不除零异常")
    void satisfactionStats_emptySafe() {
        when(sessionMapper.selectMaps(any())).thenReturn(List.of());

        TeacherService.SatisfactionStatsVO stats = service.getSatisfactionStats(tenantId);

        assertThat(stats.totalRated()).isZero();
        assertThat(stats.avgRating()).isZero();
        assertThat(stats.recentCount()).isZero();
    }

    @Test
    @DisplayName("getStats 风险分布与班级对比：有数据路径（等级分组/班级关联/排序）")
    void riskDistributionAndClassComparison_withData() {
        UUID studentA = UUID.randomUUID();
        UUID studentB = UUID.randomUUID();
        UUID studentC = UUID.randomUUID();
        UUID studentNoClass = UUID.randomUUID();
        User ua = new User(); ua.setUserId(studentA); ua.setClassCode("C1");
        User ub = new User(); ub.setUserId(studentB); ub.setClassCode("C1");
        User uc = new User(); uc.setUserId(studentC); uc.setClassCode("C2");
        User un = new User(); un.setUserId(studentNoClass); un.setClassCode(null); // 无班级 → 被过滤
        when(userMapper.selectList(any())).thenReturn(List.of(ua, ub, uc, un));

        RiskEvent e1 = RiskEvent.fromDetection(tenantId, studentA, UUID.randomUUID(), "self_harm", 3);
        RiskEvent e2 = RiskEvent.fromDetection(tenantId, studentB, UUID.randomUUID(), "bullying", 1);
        RiskEvent e3 = RiskEvent.fromDetection(tenantId, studentC, UUID.randomUUID(), "anxiety", 2);
        RiskEvent e4 = RiskEvent.fromDetection(tenantId, studentNoClass, UUID.randomUUID(), "anxiety", 2); // 计入分布、不计入班级对比
        when(riskEventMapper.selectList(any())).thenReturn(List.of(e1, e2, e3, e4));

        TeacherService.StatsVO stats = service.getStats(tenantId, null);

        // 风险等级分布：黄色=1（level1）、橙色=2（level2）、红色=1（level3）
        assertThat(stats.riskDistribution()).containsExactly(
                new TeacherService.RiskDistItem(1, "黄色", 1),
                new TeacherService.RiskDistItem(2, "橙色", 2),
                new TeacherService.RiskDistItem(3, "红色", 1));
        // 班级对比：无班级学生被过滤；按 alertCount 降序 C1(2) → C2(1)
        assertThat(stats.classComparison()).containsExactly(
                new TeacherService.ClassRiskItem("C1", 2, 2),
                new TeacherService.ClassRiskItem("C2", 1, 1));
    }

    @Test
    @DisplayName("getStats 班级范围：classScope 过滤 + 空班级返回全空")
    void classScopeFilterAndEmptyClass() {
        // 空班级：本班学生为空集合 → 直接返回全空 VO
        when(sessionAccessService.listClassStudents(tenantId, "C9")).thenReturn(List.of());
        when(userMapper.selectList(any())).thenReturn(List.of());
        TeacherService.StatsVO empty = service.getStats(tenantId, "C9");
        assertThat(empty.riskDistribution()).isEmpty();
        assertThat(empty.classComparison()).isEmpty();
        assertThat(empty.sessionTrend()).isEmpty();
        assertThat(empty.emotionDistribution()).isEmpty();

        // 有数据班级：仅统计本班学生
        UUID studentA = UUID.randomUUID();
        User ua = new User(); ua.setUserId(studentA); ua.setClassCode("C1");
        when(sessionAccessService.listClassStudents(tenantId, "C1")).thenReturn(List.of(ua));
        when(userMapper.selectList(any())).thenReturn(List.of(ua));
        RiskEvent e1 = RiskEvent.fromDetection(tenantId, studentA, UUID.randomUUID(), "self_harm", 3);
        when(riskEventMapper.selectList(any())).thenReturn(List.of(e1));

        TeacherService.StatsVO stats = service.getStats(tenantId, "C1");

        assertThat(stats.riskDistribution().get(2).count()).isEqualTo(1); // 红色=1
        assertThat(stats.classComparison()).containsExactly(
                new TeacherService.ClassRiskItem("C1", 1, 1));
    }
}
