package com.mindsafe.service.billing;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 权益检查器（BILL-001，design/38 §三/§4.2/§4.3）
 * <p>
 * bool 权益模型：功能开关（无权益 → 403）。
 * 配额（quota）模型目标态设计保留于 design/38，因当前无用额数据源，
 * 代码不预建（YAGNI），接入计量后再实现。
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

    /** 权益检查结果 */
    public record CheckResult(
            boolean allowed,
            int httpStatus,     // 200=放行, 403=无权益
            String code,        // 业务错误码
            String message
    ) {
        public static CheckResult pass() {
            return new CheckResult(true, 200, null, null);
        }

        public static CheckResult noFeature() {
            return new CheckResult(false, 403, "30002", "当前版本不包含此功能");
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
     * 判断请求路径是否在豁免清单中（硬编码，不可配置覆盖）。
     */
    public boolean isExempt(String requestPath) {
        if (requestPath == null) return false;
        return EXEMPT_PATH_PREFIXES.stream().anyMatch(requestPath::startsWith);
    }
}
