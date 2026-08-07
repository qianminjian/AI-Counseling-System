package com.mindsafe.service.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 会话访问与归属校验实现（T4 批次A）。
 * <p>
 * 租户条件在方法内部强制构造（{@code eq(tenantId)} 恒在），
 * 调用方只传 sessionId 不可能触达其他租户的会话。
 */
@Service
public class SessionAccessServiceImpl implements SessionAccessService {

    private final CounselingSessionMapper sessionMapper;

    public SessionAccessServiceImpl(CounselingSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
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
}
