package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 情绪日记 API（学生每日打卡 + 历史趋势）
 */
@RestController
@RequestMapping("/api/v1/diary")
public class EmotionDiaryController {

    private final EmotionDiaryMapper diaryMapper;

    public EmotionDiaryController(EmotionDiaryMapper diaryMapper) {
        this.diaryMapper = diaryMapper;
    }

    /** 今日打卡（每天仅一次，重复提交覆盖） */
    @PostMapping("/checkin")
    public ApiResponse<EmotionDiary> checkin(@RequestBody Map<String, Object> body, Authentication auth) {
        TenantContext ctx = extractContext(auth);

        String emotion = (String) body.getOrDefault("emotionLabel", "neutral");
        int intensity = body.containsKey("intensity") ? ((Number) body.get("intensity")).intValue() : 3;
        String note = (String) body.get("note");

        LocalDate today = LocalDate.now();

        // 查找今日已有记录
        EmotionDiary existing = diaryMapper.selectOne(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, ctx.tenantId())
                        .eq(EmotionDiary::getStudentUserId, ctx.userId())
                        .eq(EmotionDiary::getDiaryDate, today)
        );

        if (existing != null) {
            // 覆盖更新
            existing.setEmotionLabel(emotion);
            existing.setIntensity(intensity);
            existing.setNote(note);
            diaryMapper.updateById(existing);
            return ApiResponse.ok(existing);
        }

        EmotionDiary diary = EmotionDiary.create(ctx.tenantId(), ctx.userId(), emotion, intensity, note);
        diaryMapper.insert(diary);
        return ApiResponse.ok(diary);
    }

    /** 获取今日打卡状态 */
    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> getToday(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        EmotionDiary today = diaryMapper.selectOne(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, ctx.tenantId())
                        .eq(EmotionDiary::getStudentUserId, ctx.userId())
                        .eq(EmotionDiary::getDiaryDate, LocalDate.now())
        );
        if (today == null) {
            return ApiResponse.ok(Map.of("checkedIn", false));
        }
        return ApiResponse.ok(Map.of("checkedIn", true, "diary", today));
    }

    /** 近 N 天历史（默认 14 天，用于趋势图） */
    @GetMapping("/history")
    public ApiResponse<List<EmotionDiary>> getHistory(
            @RequestParam(defaultValue = "14") int days, Authentication auth) {
        TenantContext ctx = extractContext(auth);
        LocalDate since = LocalDate.now().minusDays(days);
        List<EmotionDiary> history = diaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, ctx.tenantId())
                        .eq(EmotionDiary::getStudentUserId, ctx.userId())
                        .ge(EmotionDiary::getDiaryDate, since)
                        .orderByDesc(EmotionDiary::getDiaryDate)
        );
        return ApiResponse.ok(history);
    }

    /** 连续打卡天数（streak） */
    @GetMapping("/streak")
    public ApiResponse<Map<String, Object>> getStreak(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        List<EmotionDiary> all = diaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, ctx.tenantId())
                        .eq(EmotionDiary::getStudentUserId, ctx.userId())
                        .orderByDesc(EmotionDiary::getDiaryDate)
        );

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
        return ApiResponse.ok(Map.of("streak", streak, "total", all.size()));
    }

    /** 成就徽章列表（根据打卡/会话数据计算） */
    @GetMapping("/achievements")
    public ApiResponse<List<Map<String, Object>>> getAchievements(Authentication auth) {
        TenantContext ctx = extractContext(auth);

        long diaryCount = diaryMapper.selectCount(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, ctx.tenantId())
                        .eq(EmotionDiary::getStudentUserId, ctx.userId()));

        // 计算 streak
        List<EmotionDiary> all = diaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, ctx.tenantId())
                        .eq(EmotionDiary::getStudentUserId, ctx.userId())
                        .orderByDesc(EmotionDiary::getDiaryDate));
        int streak = 0;
        LocalDate expected = LocalDate.now();
        for (EmotionDiary d : all) {
            if (d.getDiaryDate().equals(expected)) { streak++; expected = expected.minusDays(1); }
            else if (d.getDiaryDate().isBefore(expected)) break;
        }

        List<Map<String, Object>> badges = new java.util.ArrayList<>();
        badges.add(badge("first_diary", "🌱", "初次记录", "完成第一次情绪打卡", diaryCount >= 1));
        badges.add(badge("streak_3", "🔥", "三天坚持", "连续打卡 3 天", streak >= 3));
        badges.add(badge("streak_7", "⭐", "一周达人", "连续打卡 7 天", streak >= 7));
        badges.add(badge("diary_10", "📚", "记录达人", "累计打卡 10 天", diaryCount >= 10));
        badges.add(badge("diary_30", "🏆", "月度之星", "累计打卡 30 天", diaryCount >= 30));
        return ApiResponse.ok(badges);
    }

    private Map<String, Object> badge(String id, String emoji, String title, String desc, boolean unlocked) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("emoji", emoji);
        m.put("title", title);
        m.put("desc", desc);
        m.put("unlocked", unlocked);
        return m;
    }

    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }
}
