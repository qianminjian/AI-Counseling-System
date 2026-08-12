package com.mindsafe.service.diary;

import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import com.mindsafe.service.achievement.BadgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmotionDiaryService 单元测试（doing/92 R-011：checkin 原子 upsert + streak 注入化）。
 * <p>
 * 覆盖：checkin 走 upsertCheckin 单条原子 SQL（不再 selectOne+insert 两步）、
 * 业务日界统一 CounselingTimeZone、streak 经 BadgeService 实例注入调用。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("情绪日记服务（R-011 原子打卡 + streak 注入）")
class EmotionDiaryServiceTest {

    @Mock private EmotionDiaryMapper diaryMapper;
    @Mock private BadgeService badgeService;

    private EmotionDiaryService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EmotionDiaryService(diaryMapper, badgeService);
    }

    @Test
    @DisplayName("checkin：单条原子 upsert（无 select 预检、无 updateById 分支）")
    void checkin_usesAtomicUpsert() {
        service.checkin(tenantId, studentUserId, "sad", 2, "有点累");

        ArgumentCaptor<EmotionDiary> captor = ArgumentCaptor.forClass(EmotionDiary.class);
        verify(diaryMapper).upsertCheckin(captor.capture());
        // 不再走两步：无 updateById 覆盖分支、无 insert 直落（仅 upsert 单条原子 SQL + 回读）
        verify(diaryMapper, never()).updateById(org.mockito.ArgumentMatchers.any(EmotionDiary.class));
        verify(diaryMapper, never()).insert(org.mockito.ArgumentMatchers.any(EmotionDiary.class));

        EmotionDiary diary = captor.getValue();
        assertThat(diary.getTenantId()).isEqualTo(tenantId);
        assertThat(diary.getStudentUserId()).isEqualTo(studentUserId);
        assertThat(diary.getEmotionLabel()).isEqualTo("sad");
        assertThat(diary.getIntensity()).isEqualTo(2);
        assertThat(diary.getNote()).isEqualTo("有点累");
        // R-010：业务日界统一（与实现同源，不依赖机器时区）
        assertThat(diary.getDiaryDate()).isEqualTo(com.mindsafe.service.common.CounselingTimeZone.today());
    }

    @Test
    @DisplayName("P2-2（板块06）：upsertCheckin 显式 tenant_id 与调用上下文一致（@InterceptorIgnore 纵深防线）")
    void checkin_upsertTenantMatchesContext() {
        service.checkin(tenantId, studentUserId, "sad", 2, "备注");

        ArgumentCaptor<EmotionDiary> captor = ArgumentCaptor.forClass(EmotionDiary.class);
        verify(diaryMapper).upsertCheckin(captor.capture());
        EmotionDiary diary = captor.getValue();
        // 纵深防线：upsertCheckin 经 @InterceptorIgnore 绕过租户拦截，显式 tenant_id 必须
        // 与调用上下文一致且非空，否则将发生静默跨租户写入
        assertThat(diary.getTenantId()).isNotNull().isEqualTo(tenantId);
    }

    @Test
    @DisplayName("checkin：upsert 后回读今日记录返回（覆盖场景返回最终态）")
    void checkin_returnsTodayAfterUpsert() {
        EmotionDiary persisted = new EmotionDiary();
        persisted.setDiaryId(UUID.randomUUID());
        persisted.setEmotionLabel("happy");
        when(diaryMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenReturn(persisted);

        EmotionDiary result = service.checkin(tenantId, studentUserId, "happy", 5, "很好");

        assertThat(result).isSameAs(persisted);
        verify(diaryMapper).selectOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("getStreak：经 BadgeService 实例注入调用（无静态跨域耦合）")
    void getStreak_delegatesToInjectedBadgeService() {
        EmotionDiary d1 = new EmotionDiary();
        d1.setDiaryDate(LocalDate.now());
        when(diaryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(d1));
        when(badgeService.computeStreak(List.of(d1))).thenReturn(3);

        EmotionDiaryService.StreakInfo info = service.getStreak(tenantId, studentUserId);

        assertThat(info.streak()).isEqualTo(3);
        assertThat(info.total()).isEqualTo(1);
        verify(badgeService).computeStreak(eq(List.of(d1)));
    }
}
