package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 模型调用日志实体（对应 tenant_template.model_call_logs）
 */
@TableName(value = "model_call_logs", schema = TenantSchema.TENANT_TEMPLATE)
public class ModelCallLog {

    @TableId(value = "call_log_id", type = IdType.INPUT)
    private UUID callLogId;

    private UUID tenantId;
    private UUID sessionId;
    private String agentName;
    private String promptVersion;
    private String modelVersion;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Integer latencyMs;
    private String status;
    private String errorMessage;
    private Instant createdAt;

    public ModelCallLog() {}

    public static ModelCallLog create(UUID tenantId, UUID sessionId, String agentName,
                                      String promptVersion, String modelVersion,
                                      int latencyMs, String status) {
        ModelCallLog log = new ModelCallLog();
        log.callLogId = UUID.randomUUID();
        log.tenantId = tenantId;
        log.sessionId = sessionId;
        log.agentName = agentName;
        log.promptVersion = promptVersion;
        log.modelVersion = modelVersion;
        log.latencyMs = latencyMs;
        log.status = status;
        log.inputTokens = 0;
        log.outputTokens = 0;
        log.totalTokens = 0;
        log.createdAt = Instant.now();
        return log;
    }

    // ===== Getters & Setters =====
    public UUID getCallLogId() { return callLogId; }
    public void setCallLogId(UUID callLogId) { this.callLogId = callLogId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }

    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
