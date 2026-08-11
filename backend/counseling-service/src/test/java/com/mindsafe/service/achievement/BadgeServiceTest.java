package com.mindsafe.service.achievement;

import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.entity.RelaxationSession;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import com.mindsafe.domain.mapper.RelaxationSessionMapper;
import com.mindsafe.service.achievement.BadgeService.Badge;
import com.mindsafe.service.toolbox.ToolboxRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BadgeService 单元测试（BA-03，DOC-074）
 * <p>
 * 覆盖：日记徽章（打卡数/连续天数各档位）、工具徽章（relaxation_sessions 数据源映射）、
 * computeStreak 纯函数、evaluate 组合（真实 ToolboxRegistry 声明完整性）。
 */
class BadgeServiceTest {

    private EmotionDiaryMapper diaryMapper;
    private RelaxationSessionMapper relaxationMapper;
    private ToolboxRegistry toolboxRegistry;
    private BadgeService badgeService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        diaryMapper = mock(EmotionDiaryMapper.class);
        relaxationMapper = mock(RelaxationSessionMapper.class);
        toolboxRegistry = new ToolboxRegistry();
        badgeService = new BadgeService(diaryMapper, relaxationMapper, toolboxRegistry);
    }

    private EmotionDiary diary(LocalDate date) {
        EmotionDiary d = new EmotionDiary();
        d.setDiaryId(UUID.randomUUID());
        d.setTenantId(tenantId);
        d.setStudentUserId(studentUserId);
        d.setEmotionLabel("happy");
        d.setIntensity(4);
        d.setDiaryDate(date);
        return d;
    }

    private RelaxationSession session(String exerciseType) {
        return RelaxationSession.create(tenantId, studentUserId, exerciseType, 60, true);
    }

    private void mockDiaries(List<EmotionDiary> diaries) {
        when(diaryMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(diaries);
    }

    private void mockSessions(List<RelaxationSession> sessions) {
        when(relaxationMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(sessions);
    }

    private Badge badgeOf(List<Badge> badges, String id) {
        return badges.stream().filter(b -> id.equals(b.id())).findFirst().orElseThrow();
    }

    // ===== 日记徽章 =====

    @Test
    @DisplayName("无记录 → 5 个日记徽章全部未解锁")
    void evaluate_emptyDiaries() {
        mockDiaries(List.of());
        mockSessions(List.of());

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "first_diary").unlocked()).isFalse();
        assertThat(badgeOf(badges, "streak_3").unlocked()).isFalse();
        assertThat(badgeOf(badges, "streak_7").unlocked()).isFalse();
        assertThat(badgeOf(badges, "diary_10").unlocked()).isFalse();
        assertThat(badgeOf(badges, "diary_30").unlocked()).isFalse();
    }

    @Test
    @DisplayName("打卡 1 次 → first_diary 解锁")
    void evaluate_firstDiary() {
        mockDiaries(List.of(diary(LocalDate.now())));
        mockSessions(List.of());

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "first_diary").unlocked()).isTrue();
        assertThat(badgeOf(badges, "streak_3").unlocked()).isFalse();
    }

    @Test
    @DisplayName("连续 7 天 → streak_3/streak_7 解锁；断档则只按连续段计算")
    void evaluate_streak() {
        LocalDate today = LocalDate.now();
        mockDiaries(List.of(
                diary(today), diary(today.minusDays(1)), diary(today.minusDays(2)),
                diary(today.minusDays(3)), diary(today.minusDays(4)),
                diary(today.minusDays(5)), diary(today.minusDays(6))));
        mockSessions(List.of());

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "streak_3").unlocked()).isTrue();
        assertThat(badgeOf(badges, "streak_7").unlocked()).isTrue();
    }

    @Test
    @DisplayName("累计 30 天 → diary_10/diary_30 解锁（连续天数仅 1，streak 徽章不解锁）")
    void evaluate_diaryCount() {
        LocalDate today = LocalDate.now();
        List<EmotionDiary> diaries = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            diaries.add(diary(today.minusDays(i * 2L)));
        }
        mockDiaries(diaries);
        mockSessions(List.of());

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "diary_10").unlocked()).isTrue();
        assertThat(badgeOf(badges, "diary_30").unlocked()).isTrue();
        assertThat(badgeOf(badges, "streak_3").unlocked()).isFalse();
    }

    // ===== computeStreak 纯函数（doing/92 R-011：去 static 后经实例调用） =====

    @Test
    @DisplayName("computeStreak 今天+昨天连续 → 2")
    void computeStreak_consecutive() {
        LocalDate today = LocalDate.now();
        int streak = badgeService.computeStreak(List.of(diary(today), diary(today.minusDays(1))));

        assertThat(streak).isEqualTo(2);
    }

    @Test
    @DisplayName("computeStreak 昨天断档 → 0")
    void computeStreak_broken() {
        LocalDate today = LocalDate.now();
        int streak = badgeService.computeStreak(List.of(diary(today.minusDays(2))));

        assertThat(streak).isEqualTo(0);
    }

    @Test
    @DisplayName("computeStreak 今天未打卡但昨天起连续 → 0（连续从今天起算）")
    void computeStreak_notToday() {
        LocalDate today = LocalDate.now();
        int streak = badgeService.computeStreak(List.of(diary(today.minusDays(1)), diary(today.minusDays(2))));

        assertThat(streak).isEqualTo(0);
    }

    // ===== 工具徽章 =====

    @Test
    @DisplayName("无练习记录 → 3 个工具徽章声明且未解锁")
    void evaluate_toolBadgesLocked() {
        mockDiaries(List.of());
        mockSessions(List.of());

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "breathing_star").unlocked()).isFalse();
        assertThat(badgeOf(badges, "mindful_frog").unlocked()).isFalse();
        assertThat(badgeOf(badges, "island_builder").unlocked()).isFalse();
    }

    @Test
    @DisplayName("breathing_* 完成 → breathing_star 解锁")
    void evaluate_breathingBadge() {
        mockDiaries(List.of());
        mockSessions(List.of(session("breathing_478")));

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "breathing_star").unlocked()).isTrue();
        assertThat(badgeOf(badges, "mindful_frog").unlocked()).isFalse();
    }

    @Test
    @DisplayName("body_scan → mindful_frog；safe_place → island_builder")
    void evaluate_toolBadgesByExerciseType() {
        mockDiaries(List.of());
        mockSessions(List.of(session("body_scan"), session("safe_place")));

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "mindful_frog").unlocked()).isTrue();
        assertThat(badgeOf(badges, "island_builder").unlocked()).isTrue();
    }

    @Test
    @DisplayName("工具箱落库 exercise_type=toolId → 对应徽章解锁（review 修正：双命名兼容）")
    void evaluate_toolBadgesByToolboxToolId() {
        mockDiaries(List.of());
        mockSessions(List.of(session("mindful_frog"), session("safe_island")));

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "mindful_frog").unlocked()).isTrue();
        assertThat(badgeOf(badges, "island_builder").unlocked()).isTrue();
        assertThat(badgeOf(badges, "breathing_star").unlocked()).isFalse();
    }

    @Test
    @DisplayName("未完成练习（completed=false）不计徽章：SQL 条件过滤契约")
    void evaluate_incompleteSessionIgnored() {
        mockDiaries(List.of());
        // mock 层不执行 SQL：先初始化 MyBatis 元数据，再断言 BadgeService 的 completed=true 查询条件契约
        initMybatisMeta(RelaxationSession.class);
        when(relaxationMapper.selectList(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RelaxationSession> wrapper =
                    inv.getArgument(0);
            assertThat(wrapper.getCustomSqlSegment()).contains("completed");
            // 参数化 SQL：completed 条件值在 paramNameValuePairs 中（true）
            assertThat(wrapper.getParamNameValuePairs().values()).contains(true);
            return List.of();
        });

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        assertThat(badgeOf(badges, "breathing_star").unlocked()).isFalse();
    }

    /** 纯单测环境无 MyBatis 启动：手动初始化实体元数据缓存（供 getCustomSqlSegment 使用） */
    private static void initMybatisMeta(Class<?> entityClass) {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, entityClass);
    }

    @Test
    @DisplayName("徽章声明完整性：rewardBadge 非空工具全部出现在 evaluate 输出")
    void evaluate_declarationComplete() {
        mockDiaries(List.of());
        mockSessions(List.of());

        List<Badge> badges = badgeService.evaluate(tenantId, studentUserId);

        List<String> declared = toolboxRegistry.listAll().stream()
                .map(ToolboxRegistry.ToolDefinition::rewardBadge)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(declared).containsExactlyInAnyOrder(
                "breathing_star", "mindful_frog", "island_builder");
        for (String badgeId : declared) {
            assertThat(badges).anyMatch(b -> badgeId.equals(b.id()));
        }
        // 无数据源工具（grounding/mood_thermometer）不声明徽章
        assertThat(badges).noneMatch(b -> "grounding_master".equals(b.id()));
    }
}
