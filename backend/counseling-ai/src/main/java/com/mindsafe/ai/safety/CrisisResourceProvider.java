package com.mindsafe.ai.safety;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 危机资源提供者（SAFE-203，design/52 §三：危机热线多租户可配置）
 * <p>
 * 当前实现：返回 {@link CrisisResources} 硬编码常量（全国热线，适用所有租户）。
 * 扩展点：未来接入 tenant_config 表后，按 tenantId 返回学校配置的本地热线
 * （如校级心理辅导站电话、区教育局热线），全国热线作为 fallback 始终追加。
 * <p>
 * 铁律（design/14/design/18 §12.3）：
 * <ul>
 *   <li>危机热线绝不交给 LLM 生成/选择</li>
 *   <li>配置变更需管理员操作，不向学生/教师暴露编辑入口</li>
 *   <li>任何配置失败 → 回落硬编码全国热线（失败安全）</li>
 * </ul>
 */
@Component
public class CrisisResourceProvider {

    /**
     * 获取适用该租户的危机热线显示文本。
     * <p>
     * 当前版本：所有租户统一返回全国热线。
     * 后续扩展：查 tenant_config → 有本地热线则追加在前方（"学校心理老师电话：xxx\n全国热线：400-161-9995"）。
     *
     * @param tenantId 租户 ID（预留，当前未使用）
     * @return 危机热线显示文本（用于安全话术拼接）
     */
    public String getCrisisHotlineText(UUID tenantId) {
        // V1：全国统一（配置化扩展预留，不新增 DB 表）
        return "全国心理援助热线：" + CrisisResources.NATIONAL_PSYCHOLOGICAL_AID + "（24 小时）";
    }

    /**
     * 获取适用该租户的紧急联系方式（报警/急救）。
     *
     * @param tenantId 租户 ID（预留）
     * @return 紧急联系方式文本
     */
    public String getEmergencyText(UUID tenantId) {
        return "急救 " + CrisisResources.EMERGENCY_MEDICAL + " / 报警 " + CrisisResources.EMERGENCY_POLICE;
    }

    /**
     * 判断是否使用分年级安全回复（低年级短句版 vs 标准版）。
     * 当前逻辑与 CrisisResources 一致：grade ≤ 2 用短句版。
     */
    public String getRedSafetyReply(int grade) {
        return grade <= 2
                ? CrisisResources.RED_SAFETY_REPLY_LOWER_GRADE
                : CrisisResources.RED_SAFETY_REPLY;
    }
}
