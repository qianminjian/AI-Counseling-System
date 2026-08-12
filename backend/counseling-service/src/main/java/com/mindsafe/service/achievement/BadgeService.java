package com.mindsafe.service.achievement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.entity.RelaxationSession;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import com.mindsafe.domain.mapper.RelaxationSessionMapper;
import com.mindsafe.service.toolbox.ToolboxRegistry;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolCategory;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolDefinition;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 成就徽章统一评估入口（BA-03，DOC-074）
 * <p>
 * 此前徽章概念双实现：EmotionDiaryService.getAchievements（已接线）与
 * ToolboxRegistry.rewardBadge 声明（零消费）。统一收敛到此单一入口：
 * diary 徽章（情绪打卡）+ 工具徽章（放松练习完成记录，exercise_type
 * 支持练习页命名与工具箱 toolId 两套来源）。
 * <p>
 * 已知边界：grounding_54321 无 relaxation 数据源（接地练习仅存在于工具箱练习流程，
 * mood-check 不落库，画像/实验消费方 frozen/39 冻结），rewardBadge 置 null 不声明。
 */
@Service
public class BadgeService {

    private final EmotionDiaryMapper diaryMapper;
    private final RelaxationSessionMapper relaxationMapper;
    private final ToolboxRegistry toolboxRegistry;

    public BadgeService(EmotionDiaryMapper diaryMapper,
                        RelaxationSessionMapper relaxationMapper,
                        ToolboxRegistry toolboxRegistry) {
        this.diaryMapper = diaryMapper;
        this.relaxationMapper = relaxationMapper;
        this.toolboxRegistry = toolboxRegistry;
    }

    /** 成就徽章 */
    public record Badge(String id, String emoji, String title, String desc, boolean unlocked) {
    }

    /** 统一评估入口：日记徽章 + 工具徽章 */
    public List<Badge> evaluate(UUID tenantId, UUID studentUserId) {
        List<EmotionDiary> diaries = diaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getTenantId, tenantId)
                        .eq(EmotionDiary::getStudentUserId, studentUserId)
                        .orderByDesc(EmotionDiary::getDiaryDate));
        List<RelaxationSession> sessions = relaxationMapper.selectList(
                new LambdaQueryWrapper<RelaxationSession>()
                        .eq(RelaxationSession::getTenantId, tenantId)
                        .eq(RelaxationSession::getStudentUserId, studentUserId)
                        .eq(RelaxationSession::getCompleted, true));

        List<Badge> badges = new ArrayList<>();
        badges.addAll(diaryBadges(diaries));
        badges.addAll(toolBadges(sessions));
        return badges;
    }

    // ===== 日记徽章（原 EmotionDiaryService.getAchievements 逻辑搬移） =====

    private List<Badge> diaryBadges(List<EmotionDiary> all) {
        long diaryCount = all.size();
        int streak = computeStreak(all);
        return List.of(
                new Badge("first_diary", "🌱", "初次记录", "完成第一次情绪打卡", diaryCount >= 1),
                new Badge("streak_3", "🔥", "三天坚持", "连续打卡 3 天", streak >= 3),
                new Badge("streak_7", "⭐", "一周达人", "连续打卡 7 天", streak >= 7),
                new Badge("diary_10", "📚", "记录达人", "累计打卡 10 天", diaryCount >= 10),
                new Badge("diary_30", "🏆", "月度之星", "累计打卡 30 天", diaryCount >= 30));
    }

    /** 连续打卡天数（纯函数，EmotionDiaryService.getStreak 复用） */
    public static int computeStreak(List<EmotionDiary> all) {
        int streak = 0;
        // BUG-S-08-1 同源修复（2026-08-12）：streak 基准与打卡插入日界统一为业务时区，
        // 避免 UTC 日界窗口期 streak 计算错位（打卡数据为上海日界，此处不可用 JVM 默认时区）
        LocalDate expected = com.mindsafe.service.common.CounselingTimeZone.today();
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

    // ===== 工具徽章（ToolboxRegistry.rewardBadge 声明 + 放松练习完成记录） =====

    private List<Badge> toolBadges(List<RelaxationSession> sessions) {
        return toolboxRegistry.listAll().stream()
                .filter(t -> t.rewardBadge() != null)
                .map(t -> new Badge(t.rewardBadge(),
                        emojiOf(t.rewardBadge()), titleOf(t.rewardBadge()), descOf(t.rewardBadge()),
                        toolBadgeUnlocked(t, sessions)))
                .toList();
    }

    /**
     * 解锁判定：exercise_type 支持两套命名——放松练习页（body_scan/safe_place）与
     * 工具箱 mood-check 落库（exercise_type=toolId：mindful_frog/safe_island），
     * 任一命中即解锁（BA-03 review 修正：原实现仅匹配练习页命名，工具箱路径 2/3 徽章永不可达）。
     */
    private boolean toolBadgeUnlocked(ToolDefinition tool, List<RelaxationSession> sessions) {
        return switch (tool.category()) {
            case BREATHING -> sessions.stream().anyMatch(s -> s.getExerciseType().startsWith("breathing"));
            case MINDFULNESS -> sessions.stream().anyMatch(s ->
                    "body_scan".equals(s.getExerciseType()) || tool.toolId().equals(s.getExerciseType()));
            case SAFETY_PLAN -> sessions.stream().anyMatch(s ->
                    "safe_place".equals(s.getExerciseType()) || tool.toolId().equals(s.getExerciseType()));
            default -> false;
        };
    }

    private static String emojiOf(String badgeId) {
        return switch (badgeId) {
            case "breathing_star" -> "🫧";
            case "mindful_frog" -> "🐸";
            case "island_builder" -> "🏝️";
            default -> "🎖️";
        };
    }

    private static String titleOf(String badgeId) {
        return switch (badgeId) {
            case "breathing_star" -> "呼吸之星";
            case "mindful_frog" -> "正念小青蛙";
            case "island_builder" -> "安全小岛建造者";
            default -> badgeId;
        };
    }

    private static String descOf(String badgeId) {
        return switch (badgeId) {
            case "breathing_star" -> "完成一次呼吸放松练习";
            case "mindful_frog" -> "完成一次正念身体扫描";
            case "island_builder" -> "完成一次安全空间练习";
            default -> "";
        };
    }
}
