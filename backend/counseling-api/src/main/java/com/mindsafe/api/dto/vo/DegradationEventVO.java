package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.DegradationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 降级事件 VO（F9：ops 降级事件时间线响应，替代实体直接暴露）。
 */
public record DegradationEventVO(
        UUID eventId,
        String point,
        String fromState,
        String toState,
        String triggerType,
        String operator,
        String detail,
        Instant occurredAt,
        String dedupKey
) {
    public static DegradationEventVO from(DegradationEvent e) {
        return new DegradationEventVO(e.getEventId(), e.getPoint(), e.getFromState(), e.getToState(),
                e.getTriggerType(), e.getOperator(), e.getDetail(), e.getOccurredAt(), e.getDedupKey());
    }
}
