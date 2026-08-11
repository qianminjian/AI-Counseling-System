package com.mindsafe.common.util;

/**
 * 客户端 IP 解析（doing/90 P-001 收敛，AC-90-03/04/05）
 * <p>
 * 语义统一为「X-Forwarded-For 取最右非空段」——代理在右侧追加真实 IP，
 * 客户端伪造的前缀在左侧被忽略（最左可伪造，最右不可伪造）。
 * 收敛自 VoiceprintDomain.resolveClientIp（最右，正确）与 AuditLogService
 * extractClientIp（最左，可被伪造 XFF 污染审计）两套相反实现。
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /**
     * 从 X-Forwarded-For 解析真实客户端 IP（最右非空段）。
     *
     * @param xff      X-Forwarded-For 头原值（可为 null/空/脏数据）
     * @param fallback 无 XFF 时的回退（X-Real-IP 或 remoteAddr）
     * @return 解析出的客户端 IP；xff 无有效段时返回 fallback
     */
    public static String parseClientIp(String xff, String fallback) {
        if (xff != null && !xff.isBlank()) {
            // 从右往左取第一个非空段（尾随逗号/重复逗号等脏数据 → 跳过空段）
            String[] entries = xff.split(",");
            for (int i = entries.length - 1; i >= 0; i--) {
                String entry = entries[i].trim();
                if (!entry.isEmpty()) {
                    return entry;
                }
            }
        }
        return fallback;
    }
}
