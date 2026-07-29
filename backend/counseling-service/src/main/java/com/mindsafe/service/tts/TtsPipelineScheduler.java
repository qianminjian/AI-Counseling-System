package com.mindsafe.service.tts;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TTS 延迟流水线与性能降级（TTSFX-003，design/37 M3）
 * <p>
 * <ul>
 *   <li>延迟流水线：首句完整即送 TTS → 首音频返回即播 → 后续并行合成按序入队</li>
 *   <li>性能预算：首音频 P90 ≤ 1.5s，播放间隙 < 300ms</li>
 *   <li>帧率降级：连续 < 24fps → 自动切降级模式（Lottie→静态，粒子禁用）</li>
 *   <li>降级模式：所有 Lottie 换静态首帧，过渡动效换 150ms 淡入淡出</li>
 * </ul>
 * 纯函数实现。接线时由前端帧率检测 + TTS 调度消费。
 */
@Component
public class TtsPipelineScheduler {

    /** 首音频延迟预算（ms） */
    public static final long FIRST_AUDIO_BUDGET_MS = 1500;

    /** 播放间隙预算（ms） */
    public static final long PLAYBACK_GAP_BUDGET_MS = 300;

    /** 帧率底线（fps） */
    public static final int FPS_BASELINE = 30;

    /** 降级触发帧率（连续低于此值触发） */
    public static final int FPS_DEGRADE_THRESHOLD = 24;

    /** 连续低帧采样次数（达到即降级） */
    public static final int FPS_DEGRADE_SAMPLES = 5;

    // ==================== 流水线调度 ====================

    /** 句子合成任务 */
    public record SentenceTask(
            int index,
            String text,
            boolean isFirst,
            boolean fromCache
    ) {
    }

    /** 调度结果 */
    public record ScheduleResult(
            int sentenceIndex,
            boolean immediatePlay,
            boolean parallelSynth,
            String strategy
    ) {
    }

    /**
     * 为句子安排合成策略。
     * 规则：首句立即送 TTS + 立即播放；后续句子并行合成按序入队。
     *
     * @param task 句子任务
     * @return 调度结果
     */
    public ScheduleResult schedule(SentenceTask task) {
        if (task.fromCache()) {
            return new ScheduleResult(task.index(), true, false, "预合成缓存命中，零延迟直播");
        }
        if (task.isFirst()) {
            return new ScheduleResult(task.index(), true, false, "首句：完整即送 TTS → 返回即播");
        }
        return new ScheduleResult(task.index(), false, true, "后续句：并行合成，按序入播放队列");
    }

    /**
     * 判断首音频延迟是否达标。
     *
     * @param firstAudioMs 实际首音频延迟（ms）
     * @return true=达标（P90 ≤ 1.5s）
     */
    public boolean isFirstAudioWithinBudget(long firstAudioMs) {
        return firstAudioMs <= FIRST_AUDIO_BUDGET_MS;
    }

    /**
     * 判断播放间隙是否达标。
     *
     * @param gapMs 实际间隙（ms）
     * @return true=达标（< 300ms）
     */
    public boolean isPlaybackGapWithinBudget(long gapMs) {
        return gapMs < PLAYBACK_GAP_BUDGET_MS;
    }

    // ==================== 帧率降级 ====================

    /** 降级模式级别 */
    public enum DegradeLevel {
        FULL,       // 全效果
        DEGRADED    // 降级模式
    }

    /** 降级决策 */
    public record DegradeDecision(
            DegradeLevel level,
            boolean lottieEnabled,
            boolean particlesEnabled,
            boolean hapticsEnabled,
            int transitionMs,
            String reason
    ) {
    }

    /**
     * 根据帧率采样判断是否需要降级。
     *
     * @param recentFpsSamples 最近 N 次帧率采样
     * @param userDisabled     用户是否手动关闭动画
     * @return 降级决策
     */
    public DegradeDecision evaluatePerformance(List<Integer> recentFpsSamples, boolean userDisabled) {
        // 用户手动关闭
        if (userDisabled) {
            return new DegradeDecision(DegradeLevel.DEGRADED, false, false, false, 150,
                    "用户手动关闭动画效果");
        }

        // 帧率检测
        if (recentFpsSamples != null && recentFpsSamples.size() >= FPS_DEGRADE_SAMPLES) {
            int lastN = recentFpsSamples.size();
            boolean allLow = true;
            for (int i = lastN - FPS_DEGRADE_SAMPLES; i < lastN; i++) {
                if (recentFpsSamples.get(i) >= FPS_DEGRADE_THRESHOLD) {
                    allLow = false;
                    break;
                }
            }
            if (allLow) {
                return new DegradeDecision(DegradeLevel.DEGRADED, false, false, true, 150,
                        "帧率连续 " + FPS_DEGRADE_SAMPLES + " 次低于 "
                        + FPS_DEGRADE_THRESHOLD + "fps，自动降级");
            }
        }

        return new DegradeDecision(DegradeLevel.FULL, true, true, true, 300,
                "性能正常，全效果");
    }

    /**
     * 降级模式下的动效替代方案。
     *
     * @param originalType 原始动效类型
     * @param degraded     是否降级模式
     * @return 替代方案描述
     */
    public String getFallback(String originalType, boolean degraded) {
        if (!degraded) return originalType;
        return switch (originalType) {
            case "lottie" -> "static_first_frame";
            case "particle" -> "disabled";
            case "transition" -> "fade_150ms";
            case "breathing_guide" -> "countdown_numeric"; // 呼吸练习保留功能，改数字倒计时
            default -> "simplified";
        };
    }
}
