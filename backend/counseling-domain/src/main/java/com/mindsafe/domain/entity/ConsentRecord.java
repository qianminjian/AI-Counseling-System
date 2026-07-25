package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 告知同意留痕（对应 tenant_template.consent_records）
 * 版本化记录，每次告知同意版本升级需用户重新同意
 */
@TableName(value = "consent_records", schema = "tenant_template")
public class ConsentRecord {

    @TableId(value = "consent_id", type = IdType.INPUT)
    private UUID consentId;

    private UUID userId;
    private UUID tenantId;
    private String consentType;
    private String consentVersion;
    private Instant consentedAt;
    private String ipHash;
    private String userAgent;

    public static ConsentRecord create(UUID userId, UUID tenantId,
                                       String consentType, String consentVersion) {
        ConsentRecord r = new ConsentRecord();
        r.consentId = UUID.randomUUID();
        r.userId = userId;
        r.tenantId = tenantId;
        r.consentType = consentType;
        r.consentVersion = consentVersion;
        r.consentedAt = Instant.now();
        return r;
    }

    // ===== Getters & Setters =====

    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID consentId) { this.consentId = consentId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getConsentType() { return consentType; }
    public void setConsentType(String consentType) { this.consentType = consentType; }

    public String getConsentVersion() { return consentVersion; }
    public void setConsentVersion(String consentVersion) { this.consentVersion = consentVersion; }

    public Instant getConsentedAt() { return consentedAt; }
    public void setConsentedAt(Instant consentedAt) { this.consentedAt = consentedAt; }

    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
