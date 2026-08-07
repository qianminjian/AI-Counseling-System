package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.service.diary.EmotionDiaryService;
import com.mindsafe.service.diary.EmotionDiaryService.DiaryBadge;
import com.mindsafe.service.diary.EmotionDiaryService.StreakInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmotionDiaryController 单元测试（T4 批次B/C 改造版：SQL 下沉 EmotionDiaryService，Controller 仅 HTTP 层职责）
 * <p>
 * 覆盖：打卡参数解析与默认值 / 今日状态 / 历史 / streak / 徽章展示转换。
 * 域语义（upsert / streak 计算 / 徽章解锁规则）由 EmotionDiaryService 测试覆盖。
 */
class EmotionDiaryControllerTest {

    private EmotionDiaryService diaryService;
    private EmotionDiaryController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        diaryService = mock(EmotionDiaryService.class);
        controller = new EmotionDiaryController(diaryService);
    }

    private Authentication studentAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, studentUserId, "student"));
        return auth;
    }

    private EmotionDiary diary(LocalDate date, String emotion, int intensity) {
        EmotionDiary d = new EmotionDiary();
        d.setDiaryId(UUID.randomUUID());
        d.setTenantId(tenantId);
        d.setStudentUserId(studentUserId);
        d.setEmotionLabel(emotion);
        d.setIntensity(intensity);
        d.setDiaryDate(date);
        return d;
    }

    // ===== 打卡 =====

    @Test
    @DisplayName("checkin 首次打卡 → 调服务并返回新建记录")
    void checkin_new() {
        EmotionDiary created = diary(LocalDate.now(), "sad", 2);
        when(diaryService.checkin(tenantId, studentUserId, "sad", 2, "有点累")).thenReturn(created);

        var resp = controller.checkin(Map.of("emotionLabel", "sad", "intensity", 2, "note", "有点累"), studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().getEmotionLabel()).isEqualTo("sad");
        assertThat(resp.data().getDiaryDate()).isEqualTo(LocalDate.now());
        verify(diaryService).checkin(tenantId, studentUserId, "sad", 2, "有点累");
    }

    @Test
    @DisplayName("checkin 今日已有记录 → 服务内覆盖更新，Controller 透传")
    void checkin_existing() {
        EmotionDiary updated = diary(LocalDate.now(), "happy", 5);
        when(diaryService.checkin(tenantId, studentUserId, "happy", 5, "很好")).thenReturn(updated);

        var resp = controller.checkin(Map.of("emotionLabel", "happy", "intensity", 5, "note", "很好"), studentAuth());

        assertThat(resp.data().getEmotionLabel()).isEqualTo("happy");
        assertThat(resp.data().getIntensity()).isEqualTo(5);
        verify(diaryService).checkin(tenantId, studentUserId, "happy", 5, "很好");
    }

    @Test
    @DisplayName("checkin 默认值 → neutral/3 透传服务")
    void checkin_defaults() {
        EmotionDiary created = diary(LocalDate.now(), "neutral", 3);
        when(diaryService.checkin(tenantId, studentUserId, "neutral", 3, null)).thenReturn(created);

        var resp = controller.checkin(Map.of(), studentAuth());

        assertThat(resp.data().getEmotionLabel()).isEqualTo("neutral");
        assertThat(resp.data().getIntensity()).isEqualTo(3);
        verify(diaryService).checkin(tenantId, studentUserId, "neutral", 3, null);
    }

    @Test
    @DisplayName("checkin 无认证 → UNAUTHORIZED")
    void checkin_unauthorized() {
        assertThatThrownBy(() -> controller.checkin(Map.of(), null))
                .isInstanceOf(BizException.class);
    }

    // ===== 今日状态 =====

    @Test
    @DisplayName("getToday 未打卡 → checkedIn=false")
    void today_notChecked() {
        when(diaryService.getToday(tenantId, studentUserId)).thenReturn(null);

        var resp = controller.getToday(studentAuth());

        assertThat(resp.data().get("checkedIn")).isEqualTo(false);
    }

    @Test
    @DisplayName("getToday 已打卡 → diary 返回")
    void today_checked() {
        when(diaryService.getToday(tenantId, studentUserId)).thenReturn(diary(LocalDate.now(), "happy", 4));

        var resp = controller.getToday(studentAuth());

        assertThat(resp.data().get("checkedIn")).isEqualTo(true);
        assertThat(resp.data().get("diary")).isNotNull();
    }

    // ===== 历史 =====

    @Test
    @DisplayName("getHistory 默认 14 天透传")
    void history_default() {
        when(diaryService.getHistory(tenantId, studentUserId, 14)).thenReturn(List.of(diary(LocalDate.now(), "happy", 4)));

        var resp = controller.getHistory(14, studentAuth());

        assertThat(resp.data()).hasSize(1);
        verify(diaryService).getHistory(tenantId, studentUserId, 14);
    }

    // ===== 连续天数 =====

    @Test
    @DisplayName("getStreak 连续 3 天 → streak=3（Service 计算）")
    void streak_threeDays() {
        when(diaryService.getStreak(tenantId, studentUserId)).thenReturn(new StreakInfo(3, 3));

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(3);
        assertThat(resp.data().get("total")).isEqualTo(3);
    }

    @Test
    @DisplayName("getStreak 断档 → streak=0（Service 计算）")
    void streak_broken() {
        when(diaryService.getStreak(tenantId, studentUserId)).thenReturn(new StreakInfo(0, 2));

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(0);
        assertThat(resp.data().get("total")).isEqualTo(2);
    }

    @Test
    @DisplayName("getStreak 今天打了昨天没有 → streak=1")
    void streak_todayOnly() {
        when(diaryService.getStreak(tenantId, studentUserId)).thenReturn(new StreakInfo(1, 2));

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(1);
    }

    @Test
    @DisplayName("getStreak 空记录 → 0")
    void streak_empty() {
        when(diaryService.getStreak(tenantId, studentUserId)).thenReturn(new StreakInfo(0, 0));

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(0);
    }

    // ===== 徽章 =====

    private List<DiaryBadge> badges(boolean first, boolean streak3, boolean streak7, boolean diary10, boolean diary30) {
        return List.of(
                new DiaryBadge("first_diary", "🌱", "初次记录", "完成第一次情绪打卡", first),
                new DiaryBadge("streak_3", "🔥", "三天坚持", "连续打卡 3 天", streak3),
                new DiaryBadge("streak_7", "⭐", "一周达人", "连续打卡 7 天", streak7),
                new DiaryBadge("diary_10", "📚", "记录达人", "累计打卡 10 天", diary10),
                new DiaryBadge("diary_30", "🏆", "月度之星", "累计打卡 30 天", diary30));
    }

    @Test
    @DisplayName("getAchievements 无记录 → 全部未解锁（Service 计算，展示层转换）")
    void achievements_empty() {
        when(diaryService.getAchievements(tenantId, studentUserId)).thenReturn(badges(false, false, false, false, false));

        var resp = controller.getAchievements(studentAuth());

        assertThat(resp.data()).hasSize(5);
        assertThat(resp.data().get(0).get("unlocked")).isEqualTo(false);
    }

    @Test
    @DisplayName("getAchievements 10 天 + 连续 3 天 → 徽章解锁状态透传")
    void achievements_unlocked() {
        when(diaryService.getAchievements(tenantId, studentUserId))
                .thenReturn(badges(true, true, false, true, false));

        var resp = controller.getAchievements(studentAuth());

        Map<String, Object> first = resp.data().stream()
                .filter(b -> "first_diary".equals(b.get("id"))).findFirst().orElseThrow();
        Map<String, Object> streak3 = resp.data().stream()
                .filter(b -> "streak_3".equals(b.get("id"))).findFirst().orElseThrow();
        Map<String, Object> diary10 = resp.data().stream()
                .filter(b -> "diary_10".equals(b.get("id"))).findFirst().orElseThrow();
        Map<String, Object> diary30 = resp.data().stream()
                .filter(b -> "diary_30".equals(b.get("id"))).findFirst().orElseThrow();
        assertThat(first.get("unlocked")).isEqualTo(true);
        assertThat(streak3.get("unlocked")).isEqualTo(true);
        assertThat(diary10.get("unlocked")).isEqualTo(true);
        assertThat(diary30.get("unlocked")).isEqualTo(false);
        assertThat(resp.data().get(0)).containsKeys("id", "emoji", "title", "desc", "unlocked");
    }
}
