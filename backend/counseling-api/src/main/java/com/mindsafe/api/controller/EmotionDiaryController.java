package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.service.achievement.BadgeService;
import com.mindsafe.service.achievement.BadgeService.Badge;
import com.mindsafe.service.diary.EmotionDiaryService;
import com.mindsafe.service.diary.EmotionDiaryService.StreakInfo;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情绪日记 API（学生每日打卡 + 历史趋势）
 */
@RestController
@RequestMapping("/api/v1/diary")
public class EmotionDiaryController {

    private final EmotionDiaryService diaryService;
    private final BadgeService badgeService;

    public EmotionDiaryController(EmotionDiaryService diaryService, BadgeService badgeService) {
        this.diaryService = diaryService;
        this.badgeService = badgeService;
    }

    /** 今日打卡（每天仅一次，重复提交覆盖；T4 批次B：upsert 下沉 Service） */
    @PostMapping("/checkin")
    public ApiResponse<EmotionDiary> checkin(@RequestBody Map<String, Object> body, Authentication auth) {
        TenantContext ctx = extractContext(auth);

        String emotion = (String) body.getOrDefault("emotionLabel", "neutral");
        int intensity = body.containsKey("intensity") ? ((Number) body.get("intensity")).intValue() : 3;
        String note = (String) body.get("note");

        EmotionDiary diary = diaryService.checkin(ctx.tenantId(), ctx.userId(), emotion, intensity, note);
        return ApiResponse.ok(diary);
    }

    /** 获取今日打卡状态 */
    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> getToday(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        EmotionDiary today = diaryService.getToday(ctx.tenantId(), ctx.userId());
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
        return ApiResponse.ok(diaryService.getHistory(ctx.tenantId(), ctx.userId(), days));
    }

    /** 连续打卡天数（streak）与总次数（T4 批次C：计算下沉 Service） */
    @GetMapping("/streak")
    public ApiResponse<Map<String, Object>> getStreak(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        StreakInfo info = diaryService.getStreak(ctx.tenantId(), ctx.userId());
        return ApiResponse.ok(Map.of("streak", info.streak(), "total", info.total()));
    }

    /** 成就徽章列表（BA-03：BadgeService 统一评估入口：日记徽章 + 工具徽章） */
    @GetMapping("/achievements")
    public ApiResponse<List<Map<String, Object>>> getAchievements(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        return ApiResponse.ok(badgeService.evaluate(ctx.tenantId(), ctx.userId())
                .stream().map(EmotionDiaryController::toBadgeMap).toList());
    }

    private static Map<String, Object> toBadgeMap(Badge b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("emoji", b.emoji());
        m.put("title", b.title());
        m.put("desc", b.desc());
        m.put("unlocked", b.unlocked());
        return m;
    }

    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }
}
