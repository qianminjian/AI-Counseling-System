package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 告警事件实体（对应 tenant_template.alert_events，V34）
 * <p>
 * OPS-MON-008：AlertManager 告警采集落库（source=alertmanager）+ AlertService
 * 业务告警同步写（source=alertservice），供管理端 M2 告警中心历史查询。
 * 设计见 doing/83 服务降级监控 §6.4。
 */
@TableName(value = "alert_events", schema = "tenant_template")
public class AlertEvent {

    /** 来源：AlertManager 采集器拉取 */
    public static final String SOURCE_ALERTMANAGER = "alertmanager";

    /** 来源：后端业务告警（AlertService）同步写 */
    public static final String SOURCE_ALERTSERVICE = "alertservice";

    /** 状态：触发中 */
    public static final String STATUS_FIRING = "firing";

    /** 状态：已恢复 */
    public static final String STATUS_RESOLVED = "resolved";

    /** 状态：已确认（管理端 ack） */
    public static final String STATUS_ACK = "ack";

    /** 状态：已关闭（管理端 close） */
    public static final String STATUS_CLOSED = "closed";

    /** 推送状态：已入库待推送（业务告警，企微通道） */
    public static final String NOTIFY_PENDING = "PENDING";
    /** 推送状态：企微推送成功 */
    public static final String NOTIFY_SUCCESS = "SUCCESS";
    /** 推送状态：企微推送失败（附加通道失败仅标识，不影响数据链路） */
    public static final String NOTIFY_FAILED = "FAILED";
    /** 推送状态：无推送通道（日志降级通道 LoggingAlertService） */
    public static final String NOTIFY_SKIPPED = "SKIPPED";

    @TableId(value = "event_id", type = IdType.INPUT)
    private UUID eventId;

    /** alertmanager / alertservice */
    private String source;

    /** AlertManager 告警指纹（upsert 去重键，alertservice 来源为 null） */
    private String fingerprint;

    /** 规则名（如 TtsPrimaryEngineDegraded） */
    private String ruleName;

    /** CRITICAL/WARNING/INFO */
    private String severity;

    /** firing/resolved/ack/closed */
    private String status;

    /** 摘要 */
    private String summary;

    /** 详情 */
    private String detail;

    /** 确认人（管理端 ack） */
    private String acknowledgedBy;

    /** 确认时间 */
    private Instant acknowledgedAt;

    /** 触发时间 */
    private Instant firedAt;

    /** 恢复时间 */
    private Instant resolvedAt;

    /** 落库时间 */
    private Instant createdAt;

    /** 推送状态（V40）：PENDING/SUCCESS/FAILED/SKIPPED（alertservice 来源）；alertmanager 来源为 null */
    private String notifyStatus;

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(String acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public Instant getFiredAt() {
        return firedAt;
    }

    public void setFiredAt(Instant firedAt) {
        this.firedAt = firedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getNotifyStatus() {
        return notifyStatus;
    }

    public void setNotifyStatus(String notifyStatus) {
        this.notifyStatus = notifyStatus;
    }
}
