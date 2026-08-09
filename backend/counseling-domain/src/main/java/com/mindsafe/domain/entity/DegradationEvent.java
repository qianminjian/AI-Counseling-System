package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 降级事件实体（对应 tenant_template.degradation_events，V34）
 * <p>
 * OPS-MON-007/008：自动降级（auto，检测器写）与手动切换（manual，管理端 API 写）
 * 统一事件时间线，供管理端 M3 降级矩阵消费。设计见 doing/83 服务降级监控 §6.5。
 */
@TableName(value = "degradation_events", schema = "tenant_template")
public class DegradationEvent {

    /** 触发方式：监控检测器自动落库 */
    public static final String TRIGGER_AUTO = "auto";

    /** 触发方式：管理端手动切换写库 */
    public static final String TRIGGER_MANUAL = "manual";

    @TableId(value = "event_id", type = IdType.INPUT)
    private UUID eventId;

    /** 降级点: llm/tts/asr/ser/voice-policy/wake-word */
    private String point;

    /** 切换前档位 */
    private String fromState;

    /** 切换后档位 */
    private String toState;

    /** auto/manual */
    private String triggerType;

    /** 手动切换操作人（auto 为 null） */
    private String operator;

    /** 原因/影响 */
    private String detail;

    /** 事件时间 */
    private Instant occurredAt;

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getPoint() {
        return point;
    }

    public void setPoint(String point) {
        this.point = point;
    }

    public String getFromState() {
        return fromState;
    }

    public void setFromState(String fromState) {
        this.fromState = fromState;
    }

    public String getToState() {
        return toState;
    }

    public void setToState(String toState) {
        this.toState = toState;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
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

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
