package com.mindsafe.service.experiment;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 实验分桶引擎（AB-001，design/39 §2.2）
 * <p>
 * 确定性哈希分桶：同一班级永远同组，无需存储分配即可复现。
 * <ul>
 *   <li>分配单元：班级（整群随机，同班不混组）</li>
 *   <li>分桶算法：bucket = murmur3(experiment_id + ":" + class_id + salt) % 100</li>
 *   <li>bucket < 50 → control（标准 Prompt）</li>
 *   <li>bucket ≥ 50 → treatment（画像+年龄适配 Prompt）</li>
 * </ul>
 * <p>
 * 豁免规则（design/39 §2.2）：个案跟踪中(S1+历史)的学生强制 treatment 且不计入分析集。
 * 安全不实验：safety/风险检测/危机流程永远全量一致（红线）。
 */
@Component
public class ExperimentBucketAssigner {

    /** 分桶变体 */
    public enum Variant {
        CONTROL,    // 标准 Prompt
        TREATMENT   // 画像+年龄适配 Prompt
    }

    /** 分桶结果 */
    public record Assignment(
            Variant variant,
            int bucket,
            boolean exempt,       // 是否豁免（强制 treatment 且不入分析集）
            boolean analyzable    // 是否计入分析集
    ) {
    }

    /**
     * 为班级分配实验变体（确定性，同 salt 万次重算一致）。
     *
     * @param experimentId 实验 ID
     * @param classId      班级 ID
     * @param salt         实验盐值（调整年级均衡用）
     * @return 分桶结果
     */
    public Assignment assignClass(String experimentId, String classId, String salt) {
        int bucket = computeBucket(experimentId, classId, salt);
        Variant variant = bucket < 50 ? Variant.CONTROL : Variant.TREATMENT;
        return new Assignment(variant, bucket, false, true);
    }

    /**
     * 为个案豁免学生分配（强制 treatment，不入分析集）。
     * <p>
     * 伦理：不让高风险学生用"较弱"版本；统计：本就应排除。
     */
    public Assignment assignExempt(String experimentId, String studentId) {
        return new Assignment(Variant.TREATMENT, -1, true, false);
    }

    /**
     * 校验两组年级分布均衡（design/39 §2.2 分层约束）。
     * <p>
     * 三段（1-2/3-4/5-6）各组占比差 < 15% 为均衡。
     *
     * @param controlClasses   控制组班级列表（含年级）
     * @param treatmentClasses 实验组班级列表（含年级）
     * @return true=均衡，false=需调整 salt 重分
     */
    public boolean isBalanced(List<Integer> controlGrades, List<Integer> treatmentGrades) {
        if (controlGrades.isEmpty() || treatmentGrades.isEmpty()) return true;

        double[] controlDist = gradeDistribution(controlGrades);
        double[] treatmentDist = gradeDistribution(treatmentGrades);

        for (int i = 0; i < 3; i++) {
            if (Math.abs(controlDist[i] - treatmentDist[i]) > 0.15) {
                return false;
            }
        }
        return true;
    }

    /**
     * 确定性哈希分桶（Murmur3 简化版，取正模 100）。
     */
    int computeBucket(String experimentId, String classId, String salt) {
        String key = experimentId + ":" + classId + ":" + (salt == null ? "" : salt);
        int hash = murmur3Hash(key);
        return Math.floorMod(hash, 100);
    }

    /**
     * 年级分布（三段：1-2 / 3-4 / 5-6）。
     */
    private double[] gradeDistribution(List<Integer> grades) {
        int low = 0, mid = 0, high = 0;
        for (int g : grades) {
            if (g <= 2) low++;
            else if (g <= 4) mid++;
            else high++;
        }
        int total = grades.size();
        return new double[]{(double) low / total, (double) mid / total, (double) high / total};
    }

    /**
     * Murmur3 32-bit 哈希（简化实现，确定性即可）。
     */
    private int murmur3Hash(String input) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        int h = 0x9747b28c; // seed
        int len = data.length;
        int i = 0;

        while (len >= 4) {
            int k = (data[i] & 0xFF)
                    | ((data[i + 1] & 0xFF) << 8)
                    | ((data[i + 2] & 0xFF) << 16)
                    | ((data[i + 3] & 0xFF) << 24);
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
            i += 4;
            len -= 4;
        }

        int k = 0;
        switch (len) {
            case 3: k ^= (data[i + 2] & 0xFF) << 16;
            case 2: k ^= (data[i + 1] & 0xFF) << 8;
            case 1: k ^= (data[i] & 0xFF);
                k *= 0xcc9e2d51;
                k = Integer.rotateLeft(k, 15);
                k *= 0x1b873593;
                h ^= k;
        }

        h ^= data.length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
