package com.mindsafe.service.parent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 家长端服务（T4 批次C：学生查询 / 会话 / 消息下沉，Controller 不再直查 Mapper）。
 * <p>
 * 租户条件强制内置（双保险：即使未绑定租户上下文，显式 eq 条件仍防跨租户）。
 */
@Service
public class ParentService {

    private final UserMapper userMapper;
    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryMapper messageSummaryMapper;

    public ParentService(UserMapper userMapper,
                         CounselingSessionMapper sessionMapper,
                         MessageSummaryMapper messageSummaryMapper) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.messageSummaryMapper = messageSummaryMapper;
    }

    /** 查询同租户学生（null 表示不存在/非本租户） */
    public User getStudent(UUID tenantId, UUID studentUserId) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, tenantId)
                        .eq(User::getUserId, studentUserId)
        );
    }

    /** 近 N 天会话（同租户 + 同学生，按开始时间倒序） */
    public List<CounselingSession> getRecentSessions(UUID tenantId, UUID studentUserId, Instant since) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .ge(CounselingSession::getStartedAt, since)
                        .orderByDesc(CounselingSession::getStartedAt)
        );
    }

    /** 近 N 天学生消息摘要（同租户 + 同学生，仅学生发言，按时间倒序） */
    public List<MessageSummary> getRecentStudentMessages(UUID tenantId, UUID studentUserId, Instant since) {
        return messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getStudentUserId, studentUserId)
                        .eq(MessageSummary::getSenderType, User.USER_TYPE_STUDENT)
                        .ge(MessageSummary::getCreatedAt, since)
        );
    }
}
