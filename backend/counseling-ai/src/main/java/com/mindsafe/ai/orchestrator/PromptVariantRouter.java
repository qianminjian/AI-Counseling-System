package com.mindsafe.ai.orchestrator;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * EMO-001 A/B 灰度路由器（ORCH-007，design/44 P2）
 * <p>
 * 不同开场策略经 PromptVersionService 灰度：
 * <ul>
 *   <li>变体 A（control）：当前 EMO-001 标准开场</li>
 *   <li>变体 B（treatment）：新开场策略（如更主动的情绪命名）</li>
 * </ul>
 * <p>
 * 分桶算法：hash(studentId + experimentSalt) % 100，< 50 = A，≥ 50 = B。
 * 确定性：同一学生永远同一变体（无需存储）。
 * <p>
 * 安全红线：CRISIS 状态不参与实验，强制走 A（标准）。
 */
@Component
public class PromptVariantRouter {

    /** 变体标识 */
    public enum Variant {
        A,  // control：标准 EMO-001
        B   // treatment：新开场策略
    }

    /** 路由结果 */
    public record RouteResult(
            Variant variant,
            int bucket,
            boolean forcedBySafety  // 是否被安全强制（CRISIS 不参与实验）
    ) {
    }

    /**
     * 为学生路由 EMO-001 变体。
     *
     * @param studentId    学生 ID
     * @param salt         实验盐值
     * @param emotionState 当前情绪状态（CRISIS 强制 A）
     * @return 路由结果
     */
    public RouteResult route(String studentId, String salt, StrategyProfile.EmotionState emotionState) {
        // 安全红线：CRISIS 不参与实验
        if (emotionState == StrategyProfile.EmotionState.CRISIS) {
            return new RouteResult(Variant.A, -1, true);
        }

        int bucket = computeBucket(studentId, salt);
        Variant variant = bucket < 50 ? Variant.A : Variant.B;
        return new RouteResult(variant, bucket, false);
    }

    /**
     * 判断变体是否生效（灰度比例控制）。
     *
     * @param trafficPercent 灰度流量百分比（0-100，0=全 A，100=全 B）
     * @param bucket         学生桶号
     * @return true=使用 B 变体
     */
    public boolean isVariantBActive(int trafficPercent, int bucket) {
        if (trafficPercent <= 0) return false;
        if (trafficPercent >= 100) return true;
        return bucket < trafficPercent;
    }

    private int computeBucket(String studentId, String salt) {
        String key = studentId + ":" + (salt == null ? "" : salt);
        int hash = simpleHash(key);
        return Math.floorMod(hash, 100);
    }

    private int simpleHash(String input) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        int h = 0x811c9dc5;
        for (byte b : data) {
            h ^= b;
            h *= 0x01000193;
        }
        return h;
    }
}
