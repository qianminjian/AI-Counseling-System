package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmotionDiaryController 单元测试（P1 覆盖率冲刺：打卡/今日状态/历史/连续天数/徽章）
 */
class EmotionDiaryControllerTest {

    private EmotionDiaryMapper diaryMapper;
    private EmotionDiaryController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), EmotionDiary.class);

        diaryMapper = mock(EmotionDiaryMapper.class);
        controller = new EmotionDiaryController(diaryMapper);
    }

    private Authentication studentAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(tenantId, studentUserId, "student"));
        return auth;
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

    // ===== 打卡 =====

    @Test
    @DisplayName("checkin 首次打卡 → 新建记录")
    void checkin_new() {
        when(diaryMapper.selectOne(any())).thenReturn(null);

        var resp = controller.checkin(Map.of("emotionLabel", "sad", "intensity", 2, "note", "有点累"), studentAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().getEmotionLabel()).isEqualTo("sad");
        assertThat(resp.data().getDiaryDate()).isEqualTo(LocalDate.now());
        verify(diaryMapper).<EmotionDiary>insert(any(EmotionDiary.class));
        verify(diaryMapper, never()).updateById(any(EmotionDiary.class));
    }

    @Test
    @DisplayName("checkin 今日已有记录 → 覆盖更新")
    void checkin_existing() {
        EmotionDiary existing = diary(LocalDate.now());
        existing.setEmotionLabel("sad");
        when(diaryMapper.selectOne(any())).thenReturn(existing);

        var resp = controller.checkin(Map.of("emotionLabel", "happy", "intensity", 5, "note", "很好"), studentAuth());

        assertThat(resp.data().getEmotionLabel()).isEqualTo("happy");
        assertThat(resp.data().getIntensity()).isEqualTo(5);
        verify(diaryMapper).updateById(existing);
        verify(diaryMapper, never()).<EmotionDiary>insert(any(EmotionDiary.class));
    }

    @Test
    @DisplayName("checkin 默认值 → neutral/3")
    void checkin_defaults() {
        when(diaryMapper.selectOne(any())).thenReturn(null);

        var resp = controller.checkin(Map.of(), studentAuth());

        assertThat(resp.data().getEmotionLabel()).isEqualTo("neutral");
        assertThat(resp.data().getIntensity()).isEqualTo(3);
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
        when(diaryMapper.selectOne(any())).thenReturn(null);

        var resp = controller.getToday(studentAuth());

        assertThat(resp.data().get("checkedIn")).isEqualTo(false);
    }

    @Test
    @DisplayName("getToday 已打卡 → diary 返回")
    void today_checked() {
        when(diaryMapper.selectOne(any())).thenReturn(diary(LocalDate.now()));

        var resp = controller.getToday(studentAuth());

        assertThat(resp.data().get("checkedIn")).isEqualTo(true);
        assertThat(resp.data().get("diary")).isNotNull();
    }

    // ===== 历史 =====

    @Test
    @DisplayName("getHistory 默认 14 天")
    void history_default() {
        when(diaryMapper.selectList(any())).thenReturn(List.of(diary(LocalDate.now())));

        var resp = controller.getHistory(14, studentAuth());

        assertThat(resp.data()).hasSize(1);
        verify(diaryMapper).selectList(any());
    }

    // ===== 连续天数 =====

    @Test
    @DisplayName("getStreak 连续 3 天 → streak=3")
    void streak_threeDays() {
        when(diaryMapper.selectList(any())).thenReturn(List.of(
                diary(LocalDate.now()),
                diary(LocalDate.now().minusDays(1)),
                diary(LocalDate.now().minusDays(2))));

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(3);
        assertThat(resp.data().get("total")).isEqualTo(3);
    }

    @Test
    @DisplayName("getStreak 昨天断档 → streak=0（今天未打卡）")
    void streak_broken() {
        when(diaryMapper.selectList(any())).thenReturn(List.of(
                diary(LocalDate.now().minusDays(1)),
                diary(LocalDate.now().minusDays(2))));

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(0);
        assertThat(resp.data().get("total")).isEqualTo(2);
    }

    @Test
    @DisplayName("getStreak 今天打了但昨天没有 → streak=1")
    void streak_todayOnly() {
        when(diaryMapper.selectList(any())).thenReturn(List.of(
                diary(LocalDate.now()),
                diary(LocalDate.now().minusDays(2))));

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(1);
    }

    @Test
    @DisplayName("getStreak 空记录 → 0")
    void streak_empty() {
        when(diaryMapper.selectList(any())).thenReturn(List.of());

        var resp = controller.getStreak(studentAuth());

        assertThat(resp.data().get("streak")).isEqualTo(0);
    }

    // ===== 徽章 =====

    @Test
    @DisplayName("getAchievements 无记录 → 全部未解锁")
    void achievements_empty() {
        when(diaryMapper.selectCount(any())).thenReturn(0L);
        when(diaryMapper.selectList(any())).thenReturn(List.of());

        var resp = controller.getAchievements(studentAuth());

        assertThat(resp.data()).hasSize(5);
        assertThat(resp.data().get(0).get("unlocked")).isEqualTo(false);
    }

    @Test
    @DisplayName("getAchievements 10 天 + 连续 3 天 → 徽章解锁")
    void achievements_unlocked() {
        when(diaryMapper.selectCount(any())).thenReturn(10L);
        when(diaryMapper.selectList(any())).thenReturn(List.of(
                diary(LocalDate.now()),
                diary(LocalDate.now().minusDays(1)),
                diary(LocalDate.now().minusDays(2))));

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
    }
}
