package com.mindsafe.service.teacher;

import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.NotificationMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.TeacherNoteMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.security.FieldEncryptionService;
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

    private TeacherService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TeacherService(riskEventMapper, sessionMapper, userMapper,
                teacherNoteMapper, notificationMapper, messageSummaryMapper, fieldEncryptionService);
        // getStats 其余部分的默认返回（本测试聚焦趋势/情绪/满意度三处）
        lenient().when(riskEventMapper.selectList(any())).thenReturn(List.of());
        lenient().when(userMapper.selectList(any())).thenReturn(List.of());
        lenient().when(sessionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(messageSummaryMapper.selectMaps(any())).thenReturn(List.of());
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
        Instant todayStart = now.truncatedTo(ChronoUnit.DAYS);
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                sessionAt(todayStart.plus(1, ChronoUnit.MINUTES)),
                sessionAt(todayStart.plus(2, ChronoUnit.MINUTES)),
                sessionAt(now.minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS))));

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
}
