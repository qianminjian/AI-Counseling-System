package com.mindsafe.service.diary;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 情绪日记服务（T4 批次B/C：打卡 upsert / 查询 / streak / 徽章下沉，Controller 不再直查 Mapper）。
 * <p>
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
        LocalDate today = LocalDate.now();

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
                        .eq(EmotionDiary::getDiaryDate, LocalDate.now())
        );
    }

    /** 近 N 天历史（默认 14 天，用于趋势图） */
    public List<EmotionDiary> getHistory(UUID tenantId, UUID studentUserId, int days) {
        LocalDate since = LocalDate.now().minusDays(days);
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
        return new StreakInfo(computeStreak(all), all.size());
    }

    /** 成就徽章列表（根据打卡数据计算） */
    public List<DiaryBadge> getAchievements(UUID tenantId, UUID studentUserId) {
        List<EmotionDiary> all = diaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, tenantId)
                        .eq(EmotionDiary::getStudentUserId, studentUserId)
                        .orderByDesc(EmotionDiary::getDiaryDate)
        );
        long diaryCount = all.size();
        int streak = computeStreak(all);

        List<DiaryBadge> badges = new ArrayList<>();
        badges.add(new DiaryBadge("first_diary", "🌱", "初次记录", "完成第一次情绪打卡", diaryCount >= 1));
        badges.add(new DiaryBadge("streak_3", "🔥", "三天坚持", "连续打卡 3 天", streak >= 3));
        badges.add(new DiaryBadge("streak_7", "⭐", "一周达人", "连续打卡 7 天", streak >= 7));
        badges.add(new DiaryBadge("diary_10", "📚", "记录达人", "累计打卡 10 天", diaryCount >= 10));
        badges.add(new DiaryBadge("diary_30", "🏆", "月度之星", "累计打卡 30 天", diaryCount >= 30));
        return badges;
    }

    private int computeStreak(List<EmotionDiary> all) {
        int streak = 0;
        LocalDate expected = LocalDate.now();
        for (EmotionDiary d : all) {
            if (d.getDiaryDate().equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (d.getDiaryDate().isBefore(expected)) {
                break;
            }
        }
        return streak;
    }

    /** 连续打卡信息 */
    public record StreakInfo(int streak, int total) {
    }

    /** 成就徽章 */
    public record DiaryBadge(String id, String emoji, String title, String desc, boolean unlocked) {
    }
}
