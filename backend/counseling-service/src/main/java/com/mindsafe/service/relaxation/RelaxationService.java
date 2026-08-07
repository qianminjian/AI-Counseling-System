package com.mindsafe.service.relaxation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.RelaxationSession;
import com.mindsafe.domain.mapper.RelaxationSessionMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 放松练习服务（T4 批次B/C：练习记录创建 + 今日计数下沉，Controller 不再直查 Mapper）。
 * <p>
 * 租户 + 学生条件强制内置。
 */
@Service
public class RelaxationService {

    private final RelaxationSessionMapper relaxationSessionMapper;

    public RelaxationService(RelaxationSessionMapper relaxationSessionMapper) {
        this.relaxationSessionMapper = relaxationSessionMapper;
    }

    /** 记录练习完成 */
    public RelaxationSession recordSession(UUID tenantId, UUID studentUserId,
                                           String exerciseType, int durationSeconds, boolean completed) {
        RelaxationSession session = RelaxationSession.create(
                tenantId, studentUserId, exerciseType, durationSeconds, completed);
        relaxationSessionMapper.insert(session);
        return session;
    }

    /** 今日已完成练习计数 */
    public long countTodayCompleted(UUID tenantId, UUID studentUserId) {
        Instant todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        return relaxationSessionMapper.selectCount(
                new LambdaQueryWrapper<RelaxationSession>()
                        .eq(RelaxationSession::getTenantId, tenantId)
                        .eq(RelaxationSession::getStudentUserId, studentUserId)
                        .eq(RelaxationSession::getCompleted, true)
                        .ge(RelaxationSession::getCreatedAt, todayStart)
        );
    }
}
