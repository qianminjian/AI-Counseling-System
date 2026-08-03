package com.mindsafe.ai.orchestrator;

import org.springframework.stereotype.Component;

/**
 * 回复情绪推导器（TTSFX-004 后端侧，design/37 §三.1 三方同源）
 * <p>
 * 由编排策略档案（{@link StrategyProfile}）以纯规则推导 AI 回复情绪标签，零 LLM：
 * <ul>
 *   <li>serious：合规锁定 / 危机态（庄重处置，前端波波锁定 hug 安抚姿态）</li>
 *   <li>soothe：情绪激活且先接住/先稳定（安抚）</li>
 *   <li>calm：情绪激活但留白低压（不施压的平静陪伴）</li>
 *   <li>happy：窗口内 + 正常推进 + 积极情绪（一起放大积极体验）</li>
 *   <li>encourage：窗口内 + 正常推进（温和推进打气）</li>
 *   <li>gentle：其余（轻柔回应；null 档案兜底，不猜测）</li>
 * </ul>
 * 标签经 SSE {@code emotion} 事件下发，前端表情状态机/TTS/主题层同源消费，
 * 禁止各消费方另取信号源（design/37 §五）。
 */
@Component
public class ReplyEmotionResolver {

    /** 推导结果：情绪标签（六类之一）+ 强度（1=STABLE / 2=ACTIVATED / 3=CRISIS 对齐） */
    public record Result(String emotion, int intensity) {
    }

    public Result resolve(StrategyProfile profile) {
        // 失败安全：编排异常不拖累对话主线，柔和兜底
        if (profile == null) {
            return new Result("gentle", 1);
        }

        // 合规红线优先：锁定/危机 → 庄重处置基调
        if (profile.safetyLocked() || profile.emotionState() == StrategyProfile.EmotionState.CRISIS) {
            return new Result("serious", 3);
        }

        if (profile.emotionState() == StrategyProfile.EmotionState.ACTIVATED) {
            // 留白低压不给情绪压力（calm）；接住/稳定情绪走安抚（soothe）
            if (profile.opening() == StrategyProfile.OpeningStrategy.LOW_PRESSURE_SPACE) {
                return new Result("calm", 2);
            }
            return new Result("soothe", 2);
        }

        // STABLE（窗口内）
        if (profile.opening() == StrategyProfile.OpeningStrategy.NORMAL_ADVANCE) {
            if ("happy".equals(profile.entryMood())) {
                return new Result("happy", 1);
            }
            return new Result("encourage", 1);
        }
        return new Result("gentle", 1);
    }
}
