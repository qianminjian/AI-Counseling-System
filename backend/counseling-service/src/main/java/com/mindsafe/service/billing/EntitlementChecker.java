package com.mindsafe.service.billing;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 权益检查器（BILL-001，design/38 §三/§4.2/§4.3）
 * <p>
 * 两层权益模型：
 * <ul>
 *   <li>bool 权益：功能开关（无权益 → 403）</li>
 *   <li>quota 权益：用量上限（超额 → 429）</li>
 * </ul>
 * <p>
 * 豁免清单（design/38 §4.3 硬编码保护，任何配置不可覆盖）：
 * <ol>
 *   <li>S0-S1 预警生成、通知、电话升级全链路</li>
 *   <li>SOS 页面与热线资源接口</li>
 *   <li>危机场景短信（计量照记，但不拒发）</li>
 *   <li>教师端预警处理工作流</li>
 * </ol>
 * 纯规则实现。接线时由 EntitlementFilter（counseling-api）调用。
 */
@Component
public class EntitlementChecker {

    /** 订阅计划 */
    public enum Plan {
        TRIAL,      // 试用（30天，功能受限）
        BASIC,      // 基础版
        STANDARD,   // 标准版
        PREMIUM     // 旗舰版
    }

    /** 功能权益键 */
    public static final String FEAT_AI_CHAT = "ai_chat";
    public static final String FEAT_TTS = "tts";
    public static final String FEAT_ASSESSMENT = "assessment";
    public static final String FEAT_EXPORT = "export";
    public static final String FEAT_PARENT_H5 = "parent_h5";
    public static final String FEAT_VOICE_INPUT = "voice_input";
    public static final String FEAT_DATA_DASHBOARD = "data_dashboard";

    /** 配额指标键 */
    public static final String QUOTA_AI_SESSION = "ai_chat_session";
    public static final String QUOTA_TTS_MINUTE = "tts_minute";
    public static final String QUOTA_SMS_SEND = "sms_send";
    public static final String QUOTA_API_CALL = "api_call";

    /** 豁免路径前缀（硬编码，不可配置覆盖） */
    private static final Set<String> EXEMPT_PATH_PREFIXES = Set.of(
            "/api/v1/alerts",          // 预警全链路
            "/api/v1/sos",             // SOS 页面
            "/api/v1/crisis",          // 危机资源
            "/api/v1/teacher/alerts"   // 教师预警处理
    );

    /** 各计划功能权益矩阵 */
    private static final Map<Plan, Set<String>> PLAN_FEATURES = Map.of(
            Plan.TRIAL, Set.of(FEAT_AI_CHAT, FEAT_TTS),
            Plan.BASIC, Set.of(FEAT_AI_CHAT, FEAT_TTS, FEAT_PARENT_H5),
            Plan.STANDARD, Set.of(FEAT_AI_CHAT, FEAT_TTS, FEAT_PARENT_H5,
                    FEAT_ASSESSMENT, FEAT_EXPORT, FEAT_VOICE_INPUT),
            Plan.PREMIUM, Set.of(FEAT_AI_CHAT, FEAT_TTS, FEAT_PARENT_H5,
                    FEAT_ASSESSMENT, FEAT_EXPORT, FEAT_VOICE_INPUT, FEAT_DATA_DASHBOARD)
    );

    /** 各计划月度配额上限 */
    private static final Map<Plan, Map<String, Long>> PLAN_QUOTAS = Map.of(
            Plan.TRIAL, Map.of(QUOTA_AI_SESSION, 200L, QUOTA_TTS_MINUTE, 100L,
                    QUOTA_SMS_SEND, 10L, QUOTA_API_CALL, 5000L),
            Plan.BASIC, Map.of(QUOTA_AI_SESSION, 2000L, QUOTA_TTS_MINUTE, 500L,
                    QUOTA_SMS_SEND, 100L, QUOTA_API_CALL, 50000L),
            Plan.STANDARD, Map.of(QUOTA_AI_SESSION, 10000L, QUOTA_TTS_MINUTE, 2000L,
                    QUOTA_SMS_SEND, 500L, QUOTA_API_CALL, 200000L),
            Plan.PREMIUM, Map.of(QUOTA_AI_SESSION, -1L, QUOTA_TTS_MINUTE, -1L,
                    QUOTA_SMS_SEND, -1L, QUOTA_API_CALL, -1L)  // -1 = 无限
    );

    /** 权益检查结果 */
    public record CheckResult(
            boolean allowed,
            int httpStatus,     // 200=放行, 403=无权益, 429=超额
            String code,        // 业务错误码
            String message
    ) {
        public static CheckResult pass() {
            return new CheckResult(true, 200, null, null);
        }

        public static CheckResult noFeature() {
            return new CheckResult(false, 403, "30002", "当前版本不包含此功能");
        }

        public static CheckResult quotaExceeded(String metric, long limit) {
            return new CheckResult(false, 429, "30001",
                    String.format("配额超限：%s 月度上限 %d", metric, limit));
        }
    }

    /**
     * 检查 bool 功能权益。
     */
    public CheckResult checkFeature(Plan plan, String feature, String requestPath) {
        // 豁免路径直接放行
        if (isExempt(requestPath)) {
            return CheckResult.pass();
        }
        Set<String> features = PLAN_FEATURES.getOrDefault(plan, Set.of());
        if (!features.contains(feature)) {
            return CheckResult.noFeature();
        }
        return CheckResult.pass();
    }

    /**
     * 检查 quota 配额。
     *
     * @param plan         订阅计划
     * @param metric       配额指标
     * @param currentUsage 当前已用量
     * @param requestPath  请求路径（豁免判断）
     * @return 检查结果
     */
    public CheckResult checkQuota(Plan plan, String metric, long currentUsage, String requestPath) {
        // 豁免路径直接放行（计量照记，但不拒发）
        if (isExempt(requestPath)) {
            return CheckResult.pass();
        }
        Map<String, Long> quotas = PLAN_QUOTAS.getOrDefault(plan, Map.of());
        Long limit = quotas.get(metric);
        if (limit == null || limit == -1) {
            return CheckResult.pass(); // 无限制
        }
        if (currentUsage >= limit) {
            return CheckResult.quotaExceeded(metric, limit);
        }
        return CheckResult.pass();
    }

    /**
     * 获取配额上限（供 X-RateLimit-Limit 头）。
     */
    public long getQuotaLimit(Plan plan, String metric) {
        return PLAN_QUOTAS.getOrDefault(plan, Map.of()).getOrDefault(metric, -1L);
    }

    /**
     * 计算配额使用百分比（供阈值告警：80%/95%）。
     */
    public double usagePercent(Plan plan, String metric, long currentUsage) {
        long limit = getQuotaLimit(plan, metric);
        if (limit <= 0) return 0; // 无限制不告警
        return (double) currentUsage / limit * 100;
    }

    /**
     * 判断是否触发阈值告警（80% 或 95%）。
     */
    public boolean shouldAlert(Plan plan, String metric, long currentUsage) {
        double pct = usagePercent(plan, metric, currentUsage);
        return pct >= 80.0;
    }

    /**
     * 判断请求路径是否在豁免清单中（硬编码，不可配置覆盖）。
     */
    public boolean isExempt(String requestPath) {
        if (requestPath == null) return false;
        return EXEMPT_PATH_PREFIXES.stream().anyMatch(requestPath::startsWith);
    }
}
