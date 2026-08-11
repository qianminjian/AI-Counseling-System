package com.mindsafe.service.diary;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import com.mindsafe.service.achievement.BadgeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 情绪日记服务（T4 批次B/C：打卡 upsert / 查询 / streak，Controller 不再直查 Mapper）。
 * <p>
 * 徽章评估已收敛至 {@link BadgeService} 统一入口（BA-03，DOC-074）。
 * 租户 + 学生条件强制内置在查询条件中。
 */
@Service
public class EmotionDiaryService {

    private final EmotionDiaryMapper diaryMapper;

    public EmotionDiaryService(EmotionDiaryMapper diaryMapper) {
        this.diaryMapper = diaryMapper;
    }

    /** 今日打卡（每天仅一次，重复提交覆盖） */
    @Transactional
    public EmotionDiary checkin(UUID tenantId, UUID studentUserId, String emotion, int intensity, String note) {
        // doing/92 R-010：业务日界收敛至 CounselingTimeZone
        LocalDate today = com.mindsafe.service.common.CounselingTimeZone.today();

        // 查找今日已有记录
        EmotionDiary existing = diaryMapper.selectOne(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, tenantId)
                        .eq(EmotionDiary::getStudentUserId, studentUserId)
                        .eq(EmotionDiary::getDiaryDate, today)
        );

        if (existing != null) {
            // 覆盖更新
            existing.setEmotionLabel(emotion);
            existing.setIntensity(intensity);
            existing.setNote(note);
            diaryMapper.updateById(existing);
            return existing;
        }

        EmotionDiary diary = EmotionDiary.create(tenantId, studentUserId, emotion, intensity, note);
        diaryMapper.insert(diary);
        return diary;
    }

    /** 获取今日打卡状态（null 表示未打卡） */
    public EmotionDiary getToday(UUID tenantId, UUID studentUserId) {
        return diaryMapper.selectOne(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, tenantId)
                        .eq(EmotionDiary::getStudentUserId, studentUserId)
                        .eq(EmotionDiary::getDiaryDate, com.mindsafe.service.common.CounselingTimeZone.today())
        );
    }

    /** 近 N 天历史（默认 14 天，用于趋势图） */
    public List<EmotionDiary> getHistory(UUID tenantId, UUID studentUserId, int days) {
        LocalDate since = com.mindsafe.service.common.CounselingTimeZone.today().minusDays(days);
        return diaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, tenantId)
                        .eq(EmotionDiary::getStudentUserId, studentUserId)
                        .ge(EmotionDiary::getDiaryDate, since)
                        .orderByDesc(EmotionDiary::getDiaryDate)
        );
    }

    /** 连续打卡天数（streak）与总次数 */
    public StreakInfo getStreak(UUID tenantId, UUID studentUserId) {
        List<EmotionDiary> all = diaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, tenantId)
                        .eq(EmotionDiary::getStudentUserId, studentUserId)
                        .orderByDesc(EmotionDiary::getDiaryDate)
        );
        return new StreakInfo(BadgeService.computeStreak(all), all.size());
    }

    /** 连续打卡信息 */
    public record StreakInfo(int streak, int total) {
    }
}
