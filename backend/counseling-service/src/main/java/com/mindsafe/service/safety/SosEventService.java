package com.mindsafe.service.safety;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.enums.RiskLevel;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.service.risk.RiskEventWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * SOS 打开事件服务（P0-2 审计修复，design/36 §3.4 验收标准 M2：
 * 「WHEN 学生点击 SOS THEN 系统 SHALL 在网络可用时 1min 内产生 S2 事件」）。
 * <p>
 * 前端 {@code POST /api/v1/sos/events} fire-and-forget 上报的服务端落地：
 * <ul>
 *   <li>落 S2（YELLOW）风险事件，sourceType=sos，进入教师预警队列</li>
 *   <li>5 分钟去重窗口：儿童重复点击不产生重复预警（防刷屏，不影响求助可见性）</li>
 *   <li>落库 fail-fast（安全关键记录不允许静默丢失）；教师通知尽力而为</li>
 * </ul>
 */
@Service
public class SosEventService {

    private static final Logger log = LoggerFactory.getLogger(SosEventService.class);

    /** 同一学生 SOS 事件去重窗口 */
    private static final long DEDUP_WINDOW_MINUTES = 5;

    /** 去重查询专用（近 5 分钟已有 SOS 事件判断）；写入统一走 {@link RiskEventWriter} */
    private final RiskEventMapper riskEventMapper;
    /** S-009（doing/93）：风险事件统一写入入口（SOS 需教师通知 → write(event, true)） */
    private final RiskEventWriter riskEventWriter;

    public SosEventService(RiskEventMapper riskEventMapper, RiskEventWriter riskEventWriter) {
        this.riskEventMapper = riskEventMapper;
        this.riskEventWriter = riskEventWriter;
    }

    /** SOS 上报结果 */
    public record SosResult(UUID riskEventId, boolean deduplicated) {
    }

    /**
     * 记录一次 SOS 打开事件。
     *
     * @return 新事件的 id；若命中去重窗口则 deduplicated=true 且 riskEventId=null
     */
    public SosResult recordSosEvent(UUID tenantId, UUID studentUserId) {
        // 去重窗口：近 DEDUP_WINDOW_MINUTES 分钟已有 SOS 事件则不重复落
        Long recent = riskEventMapper.selectCount(new LambdaQueryWrapper<RiskEvent>()
                .eq(RiskEvent::getTenantId, tenantId)
                .eq(RiskEvent::getStudentUserId, studentUserId)
                .eq(RiskEvent::getSourceType, "sos")
                .ge(RiskEvent::getDetectedAt, Instant.now().minus(DEDUP_WINDOW_MINUTES, ChronoUnit.MINUTES)));
        if (recent != null && recent > 0) {
            log.info("SOS 事件去重（{}分钟窗口内已有事件）: studentId={}", DEDUP_WINDOW_MINUTES, studentUserId);
            return new SosResult(null, true);
        }

        RiskEvent event = new RiskEvent();
        event.setRiskEventId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setStudentUserId(studentUserId);
        event.setSourceType("sos");
        event.setSourceId(null);
        event.setRiskType("sos_open");
        event.setRiskLevel(RiskLevel.YELLOW.severity()); // S2
        event.setDetectedBy("sos_button");
        event.setDetectedAt(Instant.now());
        event.setStatus(RiskEvent.STATUS_OPEN);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());

        try {
            // S-009（doing/93）：统一写入入口——落库 fail-fast（writer 内 insert 失败直接上抛），
            // 教师通知尽力而为（失败由 writer 标记 failed 进补偿队列 P0-4）
            riskEventWriter.write(event, true);
            log.info("SOS 事件已持久化: riskEventId={}, studentId={}", event.getRiskEventId(), studentUserId);
        } catch (Exception e) {
            log.error("SOS 事件持久化失败(fail-fast 上抛): studentId={}", studentUserId, e);
            throw new IllegalStateException("SOS 事件持久化失败", e);
        }

        return new SosResult(event.getRiskEventId(), false);
    }
}
