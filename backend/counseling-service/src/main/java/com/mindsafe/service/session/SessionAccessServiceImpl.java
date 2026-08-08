package com.mindsafe.service.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话访问与归属校验实现（T4 批次A）。
 * <p>
 * 租户条件在方法内部强制构造（{@code eq(tenantId)} 恒在），
 * 调用方只传 sessionId 不可能触达其他租户的会话。
 * B5：班级学生集合查询亦收敛于此（租户条件同样强制内置）。
 */
@Service
public class SessionAccessServiceImpl implements SessionAccessService {

    private final CounselingSessionMapper sessionMapper;
    /** B5：班级范围查询（listClassStudents）依赖 */
    private final UserMapper userMapper;

    public SessionAccessServiceImpl(CounselingSessionMapper sessionMapper, UserMapper userMapper) {
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
    }

    @Override
    public CounselingSession getTenantSession(UUID tenantId, UUID sessionId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<CounselingSession>()
                .eq(CounselingSession::getTenantId, tenantId)
                .eq(CounselingSession::getSessionId, sessionId));
    }

    @Override
    public boolean sessionBelongsToTenant(UUID tenantId, UUID sessionId) {
        return getTenantSession(tenantId, sessionId) != null;
    }

    @Override
    public List<User> listClassStudents(UUID tenantId, String classScope) {
        if (classScope == null || classScope.isBlank()) {
            return List.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getUserType, User.USER_TYPE_STUDENT)
                .eq(User::getClassCode, classScope));
    }
}
