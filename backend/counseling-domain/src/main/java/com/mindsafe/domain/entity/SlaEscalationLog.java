package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * SLA 逾期升级留痕实体（对应 tenant_template.sla_escalation_log，V36）
 * <p>
 * M8 逾期升级留痕：扫描器自动升级（notify_escalate）+ 平台转派/强制关闭。
 * 设计见 doing/83 后台管理端 §6.9。
 */
@TableName(value = "sla_escalation_log", schema = "tenant_template")
public class SlaEscalationLog {

    /** 动作：通知升级（扫描器自动） */
    public static final String ACTION_NOTIFY_ESCALATE = "notify_escalate";

    /** 动作：转派（平台操作） */
    public static final String ACTION_TRANSFER = "transfer";

    /** 动作：强制关闭（平台操作） */
    public static final String ACTION_FORCE_CLOSE = "force_close";

    @TableId(value = "escalation_id", type = IdType.INPUT)
    private UUID escalationId;

    /** 关联预警 */
    private UUID riskEventId;

    /** 超时阶段：ack/handle/follow_up */
    private String stage;

    /** SLA 应完成时间点 */
    private Instant expectedAt;

    /** 实际升级时间 */
    private Instant escalatedAt;

    /** notify_escalate/transfer/force_close */
    private String action;

    /** 平台操作人（可空=自动升级） */
    private String operator;

    /** 升级说明/处置意见 */
    private String detail;

    public UUID getEscalationId() {
        return escalationId;
    }

    public void setEscalationId(UUID escalationId) {
        this.escalationId = escalationId;
    }

    public UUID getRiskEventId() {
        return riskEventId;
    }

    public void setRiskEventId(UUID riskEventId) {
        this.riskEventId = riskEventId;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Instant getExpectedAt() {
        return expectedAt;
    }

    public void setExpectedAt(Instant expectedAt) {
        this.expectedAt = expectedAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
