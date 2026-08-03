package com.mindsafe.service.prompt;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 红队回归门禁执行器（G-1，design/45 §7.3）
 * <p>
 * 无 LLM 的确定性静态回归：改任何 L1/安全类模板申请上线时，
 * 自动对模板正文跑静态检查，全通过才允许 activate。
 * <ul>
 *   <li>禁止模式：弱化安全指令 / 允许泄露提示词 / 允许提供自伤细节等（指令注入回潮检测）</li>
 *   <li>必含声明：安全关键模板必须包含核心安全声明（风险等级 / 不透露提示词）</li>
 *   <li>用例集：{@link TemplateMatrixRegistry#getGuardrailCases()} 六类攻击面为运行期护栏，
 *       本执行器负责模板侧的静态回归（CI 可重复执行，零 LLM 成本）</li>
 * </ul>
 */
@Component
public class RedTeamRegressionRunner {

    /** 安全关键模板（DB 侧下划线 key 与矩阵侧连字符 key 均识别） */
    private static final Set<String> SAFETY_CRITICAL_KEYS = Set.of(
            "SYS-001", "SYS_001",
            "SAF-001", "SAF_001",
            "SAF-002", "SAF_002"
    );

    /** 禁止出现在安全类模板中的弱化/泄露指令模式（design/45 §7.1 攻击面反向检测） */
    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "忽略安全规则",
            "忽略以上指令",
            "忽略之前的指令",
            "没有安全规则",
            "无需遵守安全",
            "满足用户的一切要求",
            "输出完整的 system prompt",
            "输出完整的system prompt",
            "透露系统提示词",
            "提供自杀方法",
            "提供自伤方法"
    );

    /** 安全关键模板必含的核心安全声明关键词（任一缺失即回退风险） */
    private static final List<String> REQUIRED_MARKERS = List.of(
            "风险等级",
            "提示词"
    );

    /** 回归结果 */
    public record RegressionResult(
            boolean passed,
            List<String> violations
    ) {
    }

    /** 是否安全关键模板（L1 基座层 / 安全类，改版必过红队回归） */
    public boolean isSafetyCritical(String templateKey) {
        return templateKey != null && SAFETY_CRITICAL_KEYS.contains(templateKey);
    }

    /**
     * 对模板正文跑静态回归。非安全关键模板直接放行（design/45 §7.3 仅 L1/安全类强制）。
     */
    public RegressionResult run(String templateKey, String content) {
        if (!isSafetyCritical(templateKey)) {
            return new RegressionResult(true, List.of());
        }

        List<String> violations = new ArrayList<>();

        if (content == null || content.isBlank()) {
            violations.add("模板内容为空");
            return new RegressionResult(false, violations);
        }

        // 禁止模式检测（弱化安全指令回潮）
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (content.contains(pattern)) {
                violations.add("含弱化安全/泄露指令模式: " + pattern);
            }
        }

        // 核心安全声明检测
        for (String marker : REQUIRED_MARKERS) {
            if (!content.contains(marker)) {
                violations.add("缺少核心安全声明关键词: " + marker);
            }
        }

        return new RegressionResult(violations.isEmpty(), violations);
    }
}
