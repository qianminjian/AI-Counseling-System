package com.mindsafe.service.experiment;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 实验指标采集器（AB-002，design/39 M2）
 * <p>
 * 三表情满意度反馈 + 风险属实勾选 + 聚合统计。
 * <ul>
 *   <li>满意度：会话结束后隔次出现三表情反馈（😊😐😢），可跳过</li>
 *   <li>风险属实：教师处理预警时勾选"是否属实"（风险识别准确率数据源）</li>
 *   <li>聚合：按 experiment_id × variant × metric × day 聚合（n/sum/sum_sq）</li>
 * </ul>
 * 纯函数实现。接线时由会话结束异步任务 + 教师端表单消费。
 */
@Component
public class ExperimentMetricsCollector {

    /** 满意度表情 */
    public enum SatisfactionEmoji {
        HAPPY(3),    // 😊
        NEUTRAL(2),  // 😐
        SAD(1);      // 😢

        private final int score;

        SatisfactionEmoji(int score) {
            this.score = score;
        }

        public int score() {
            return score;
        }
    }

    /** 指标类型 */
    public enum MetricType {
        SATISFACTION,       // 满意度（1-3）
        SESSION_DEPTH,      // 会话深度（轮数）
        RISK_PRECISION,     // 风险属实率（0/1）
        TOOL_MOOD_DELTA,    // 工具前后心情差
        EMOTION_RECOVERY    // 情绪稳定回落速度（轮数）
    }

    /** 单条指标事件 */
    public record MetricEvent(
            String experimentId,
            String variant,       // CONTROL / TREATMENT
            String studentId,
            MetricType metric,
            double value,
            LocalDate day
    ) {
    }

    /** 日聚合结果 */
    public record DailyAggregate(
            String experimentId,
            String variant,
            MetricType metric,
            LocalDate day,
            long n,
            double sum,
            double sumSq
    ) {
        public double mean() {
            return n == 0 ? 0 : sum / n;
        }

        public double variance() {
            if (n < 2) return 0;
            double mean = mean();
            return sumSq / n - mean * mean;
        }

        public double stdDev() {
            return Math.sqrt(Math.max(0, variance()));
        }
    }

    /**
     * 判断本次会话是否应展示满意度反馈（隔次出现，避免疲劳）。
     *
     * @param sessionCount 该学生累计会话数
     * @return true=本次展示
     */
    public boolean shouldShowFeedback(int sessionCount) {
        return sessionCount > 0 && sessionCount % 2 == 0; // 隔次
    }

    /**
     * 将满意度表情转为数值。
     */
    public double satisfactionScore(SatisfactionEmoji emoji) {
        return emoji == null ? 0 : emoji.score();
    }

    /**
     * 聚合一批指标事件为日聚合。
     *
     * @param events 同 experiment × variant × metric × day 的事件列表
     * @return 日聚合结果
     */
    public DailyAggregate aggregate(List<MetricEvent> events) {
        if (events == null || events.isEmpty()) {
            return new DailyAggregate("", "", MetricType.SATISFACTION, LocalDate.now(), 0, 0, 0);
        }

        MetricEvent first = events.get(0);
        long n = events.size();
        double sum = 0;
        double sumSq = 0;

        for (MetricEvent e : events) {
            sum += e.value();
            sumSq += e.value() * e.value();
        }

        return new DailyAggregate(
                first.experimentId(), first.variant(), first.metric(), first.day(),
                n, sum, sumSq);
    }

    /**
     * 计算两组均值差异的 Cohen's d 效应量（简化版，供月度报告参考）。
     *
     * @param controlAgg   控制组聚合
     * @param treatmentAgg 实验组聚合
     * @return Cohen's d（正值=treatment 更高）
     */
    public double cohensD(DailyAggregate controlAgg, DailyAggregate treatmentAgg) {
        double pooledStd = Math.sqrt(
                (controlAgg.variance() * (controlAgg.n() - 1) + treatmentAgg.variance() * (treatmentAgg.n() - 1))
                        / Math.max(1, controlAgg.n() + treatmentAgg.n() - 2));
        if (pooledStd == 0) return 0;
        return (treatmentAgg.mean() - controlAgg.mean()) / pooledStd;
    }

    /**
     * 护栏指标检查：S0 链路时延差异是否有统计学意义（简化：均值差 > 阈值即告警）。
     *
     * @param controlMean   控制组 S0 响应均值（ms）
     * @param treatmentMean 实验组 S0 响应均值（ms）
     * @return true=差异在安全范围内（< 200ms）
     */
    public boolean isSafetyLatencyOk(double controlMean, double treatmentMean) {
        return Math.abs(treatmentMean - controlMean) < 200;
    }
}
