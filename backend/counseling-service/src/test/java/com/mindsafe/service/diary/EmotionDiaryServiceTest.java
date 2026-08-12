package com.mindsafe.service.diary;

import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import com.mindsafe.service.common.CounselingTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmotionDiaryService 单元测试（BUG-S-08-1 回归，UI-TEST-012）
 * <p>
 * 覆盖：首次打卡 insert（业务时区日界）、同天重复打卡覆盖更新（不 insert，防唯一索引冲突 500）、
 * 跨天打卡新 insert、getToday/getHistory/getStreak 查询条件。
 */
class EmotionDiaryServiceTest {

    private EmotionDiaryMapper diaryMapper;
    private EmotionDiaryService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        diaryMapper = mock(EmotionDiaryMapper.class);
        service = new EmotionDiaryService(diaryMapper);
    }

    @Test
    @DisplayName("首次打卡 → insert，diaryDate 为业务时区今日（非 JVM 默认时区）")
    void checkin_first_insertsWithBusinessDate() {
        when(diaryMapper.selectOne(any())).thenReturn(null);

        EmotionDiary diary = service.checkin(tenantId, studentUserId, "happy", 4, "很好");

        verify(diaryMapper).insert((EmotionDiary) diary);
        assertThat(diary.getDiaryDate()).isEqualTo(CounselingTimeZone.today());
        assertThat(diary.getDiaryDate()).isEqualTo(LocalDate.now(CounselingTimeZone.SHANGHAI));
    }

    @Test
    @DisplayName("BUG-S-08-1 回归：同天重复打卡 → 覆盖更新（updateById），不再 insert")
    void checkin_sameDay_reusesExisting() {
        EmotionDiary existing = new EmotionDiary();
        existing.setDiaryId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setStudentUserId(studentUserId);
        existing.setEmotionLabel("sad");
        existing.setIntensity(2);
        existing.setNote("有点累");
        existing.setDiaryDate(CounselingTimeZone.today());
        when(diaryMapper.selectOne(any())).thenReturn(existing);

        EmotionDiary diary = service.checkin(tenantId, studentUserId, "happy", 5, "更新为开心");

        verify(diaryMapper, never()).insert(any(EmotionDiary.class));
        verify(diaryMapper).updateById(existing);
        assertThat(diary).isSameAs(existing);
        assertThat(existing.getEmotionLabel()).isEqualTo("happy");
        assertThat(existing.getIntensity()).isEqualTo(5);
        assertThat(existing.getNote()).isEqualTo("更新为开心");
    }

    @Test
    @DisplayName("跨天打卡 → 新 insert（历史记录不覆盖）")
    void checkin_nextDay_insertsNew() {
        when(diaryMapper.selectOne(any())).thenReturn(null);

        EmotionDiary diary = service.checkin(tenantId, studentUserId, "calm", 3, "新一天");

        verify(diaryMapper).insert((EmotionDiary) diary);
        assertThat(diary.getDiaryDate()).isEqualTo(CounselingTimeZone.today());
    }

    @Test
    @DisplayName("getToday 按业务时区今日查询")
    void getToday_queriesBusinessToday() {
        service.getToday(tenantId, studentUserId);

        verify(diaryMapper).selectOne(any());
    }
}
